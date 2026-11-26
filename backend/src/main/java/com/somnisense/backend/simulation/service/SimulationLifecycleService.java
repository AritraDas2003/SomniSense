package com.somnisense.backend.simulation.service;

import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.audit.service.AuditAction;
import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.common.exception.ResourceNotFoundException;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.simulation.client.SimulationWorkerClient;
import com.somnisense.backend.simulation.client.SimulationWorkerException;
import com.somnisense.backend.simulation.config.SimulationProperties;
import com.somnisense.backend.simulation.dto.SimulationResponse;
import com.somnisense.backend.simulation.dto.StartSimulationRequest;
import com.somnisense.backend.simulation.dto.WorkerControlResponse;
import com.somnisense.backend.simulation.dto.WorkerStartCommand;
import com.somnisense.backend.simulation.dto.WorkerStopCommand;
import com.somnisense.backend.simulation.entity.SimulationRun;
import com.somnisense.backend.simulation.entity.SimulationStatus;
import com.somnisense.backend.simulation.repository.SimulationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.somnisense.backend.common.util.Strings.clean;

/**
 * Coordinates durable simulator lifecycle, monotonic worker generation fencing, and leases.
 *
 * All external HTTP calls to simulator-worker execute OUTSIDE database transactions so PostgreSQL row
 * locks are never held across network I/O.
 */
@Service
public class SimulationLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SimulationLifecycleService.class);
    private static final BigDecimal DEFAULT_CADENCE = new BigDecimal("1.00");
    private static final BigDecimal DEFAULT_PLAYBACK_SPEED = new BigDecimal("1.00");

    private final MonitoringSessionRepository sessionRepository;
    private final DeviceAssignmentRepository assignmentRepository;
    private final DeviceRepository deviceRepository;
    private final SimulationRunRepository simulationRunRepository;
    private final AuditService auditService;
    private final SimulationProperties properties;
    private final SimulationWorkerClient workerClient;
    private final TransactionTemplate transactionTemplate;

    public SimulationLifecycleService(MonitoringSessionRepository sessionRepository,
                                      DeviceAssignmentRepository assignmentRepository,
                                      DeviceRepository deviceRepository,
                                      SimulationRunRepository simulationRunRepository,
                                      AuditService auditService,
                                      SimulationProperties properties,
                                      SimulationWorkerClient workerClient,
                                      PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.assignmentRepository = assignmentRepository;
        this.deviceRepository = deviceRepository;
        this.simulationRunRepository = simulationRunRepository;
        this.auditService = auditService;
        this.properties = properties;
        this.workerClient = workerClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public record StartResult(SimulationResponse simulation, boolean created) {
    }

    /** Marks any pre-existing RUNNING simulator runs PAUSED on backend startup before servicing requests. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverRunningSimulationsOnStartup() {
        Integer paused = transactionTemplate.execute(tx ->
                simulationRunRepository.pauseAllRunningOnStartup(Instant.now()));
        if (paused != null && paused > 0) {
            log.warn("Marked {} orphaned RUNNING simulation_runs as PAUSED on startup recovery", paused);
        }
    }

    /** Transitions expired RUNNING leases back to PAUSED so staff can resume safely. */
    public int expireStaleLeases() {
        List<SimulationRun> expired = transactionTemplate.execute(tx ->
                simulationRunRepository.findByStatusAndLeaseExpiresAtBefore(SimulationStatus.RUNNING, Instant.now()));
        if (expired == null || expired.isEmpty()) {
            return 0;
        }
        int transitioned = 0;
        for (SimulationRun candidate : expired) {
            Boolean updated = transactionTemplate.execute(tx -> {
                SimulationRun locked = simulationRunRepository.findByIdForUpdate(candidate.getId()).orElse(null);
                if (locked == null || locked.getStatus() != SimulationStatus.RUNNING) {
                    return false;
                }
                Instant now = Instant.now();
                if (locked.getLeaseExpiresAt() != null && !locked.getLeaseExpiresAt().isAfter(now)) {
                    locked.pause(now);
                    simulationRunRepository.save(locked);
                    return true;
                }
                return false;
            });
            if (Boolean.TRUE.equals(updated)) {
                transitioned++;
                log.warn("Expired stale simulator lease for simulationRun={} session={}",
                        candidate.getId(), candidate.getSessionId());
            }
        }
        return transitioned;
    }

    public StartResult start(UUID sessionId, StartSimulationRequest req, UUID actorId) {
        StartSimulationRequest safeReq = req == null ? new StartSimulationRequest() : req;
        String scenarioId = safeReq.getScenarioId() == null || safeReq.getScenarioId().isBlank()
                ? "normal_sleep"
                : safeReq.getScenarioId().trim();
        long seed = safeReq.getSeed() != null ? safeReq.getSeed() : Math.abs(sessionId.getMostSignificantBits() & 0x7FFFFFFFL);
        BigDecimal cadence = safeReq.getCadenceHz() != null ? safeReq.getCadenceHz() : DEFAULT_CADENCE;
        BigDecimal playbackSpeed = safeReq.getPlaybackSpeed() != null ? safeReq.getPlaybackSpeed() : DEFAULT_PLAYBACK_SPEED;
        String qualityPreset = clean(safeReq.getQualityPreset());

        record PreparedDispatch(SimulationRun run, boolean created, WorkerStartCommand command) {
        }

        PreparedDispatch prepared = transactionTemplate.execute(tx -> {
            MonitoringSession session = sessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));
            if (!session.isActive()) {
                throw new ApiException(ErrorCode.INVALID_SESSION_TRANSITION,
                        "Cannot start simulation for " + session.getStatus() + " session " + session.getSessionCode());
            }
            DeviceAssignment assignment = assignmentRepository.findById(session.getDeviceAssignmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Assignment", session.getDeviceAssignmentId()));
            Device device = deviceRepository.findById(assignment.getDeviceId())
                    .orElseThrow(() -> new ResourceNotFoundException("Device", assignment.getDeviceId()));
            if (device.getDeviceType() != SourceType.SIMULATED) {
                throw new ApiException(ErrorCode.SIMULATION_DEVICE_NOT_SIMULATED);
            }
            if (!device.isActive()) {
                throw new ApiException(ErrorCode.DEVICE_NOT_AVAILABLE, "Device " + device.getDeviceCode() + " is inactive");
            }

            Optional<SimulationRun> existingOpt = simulationRunRepository.findBySessionIdForUpdate(sessionId);
            Instant now = Instant.now();
            Instant leaseExpiresAt = now.plus(properties.getLeaseDuration());

            SimulationRun run;
            boolean created;
            if (existingOpt.isPresent()) {
                run = existingOpt.get();
                if (run.getStatus() == SimulationStatus.RUNNING) {
                    throw new ApiException(ErrorCode.SIMULATION_ALREADY_RUNNING);
                }
                if (run.getStatus() == SimulationStatus.PAUSED
                        || run.getStatus() == SimulationStatus.STOPPED
                        || (run.getStatus() == SimulationStatus.FAILED && run.getLastStep() > 0)) {
                    throw new ApiException(ErrorCode.SIMULATION_INVALID_STATE,
                            "Simulation run already exists in status " + run.getStatus() + "; use /resume instead");
                }
                run.startNextGeneration(now, leaseExpiresAt);
                created = false;
            } else {
                run = new SimulationRun(
                        sessionId, scenarioId, seed, cadence, playbackSpeed, qualityPreset,
                        SimulationStatus.RUNNING, 0L, 1L, leaseExpiresAt, null, now);
                created = true;
            }
            SimulationRun saved = simulationRunRepository.saveAndFlush(run);
            auditService.record(actorId, AuditAction.SIMULATION_STARTED, "SIMULATION_RUN", saved.getId(),
                    Map.of("sessionId", sessionId.toString(),
                            "scenarioId", saved.getScenarioId(),
                            "workerGeneration", String.valueOf(saved.getWorkerGeneration())));

            WorkerStartCommand cmd = buildWorkerStartCommand(saved, session, device.getDeviceCode());
            return new PreparedDispatch(saved, created, cmd);
        });

        try {
            WorkerControlResponse ack = workerClient.startWorker(prepared.command());
            SimulationRun finalRun = recordWorkerInstanceIfPresent(prepared.run().getId(),
                    prepared.run().getWorkerGeneration(), ack);
            return new StartResult(new SimulationResponse(finalRun), prepared.created());
        } catch (SimulationWorkerException ex) {
            markFailedForGeneration(prepared.run().getId(), prepared.run().getWorkerGeneration());
            throw new ApiException(ErrorCode.SIMULATION_WORKER_UNAVAILABLE, ex.getMessage());
        }
    }

    public SimulationResponse resume(UUID sessionId, UUID actorId) {
        record PreparedDispatch(SimulationRun run, WorkerStartCommand command) {
        }

        PreparedDispatch prepared = transactionTemplate.execute(tx -> {
            MonitoringSession session = sessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));
            if (!session.isActive()) {
                throw new ApiException(ErrorCode.INVALID_SESSION_TRANSITION,
                        "Cannot resume simulation for " + session.getStatus() + " session " + session.getSessionCode());
            }
            SimulationRun run = simulationRunRepository.findBySessionIdForUpdate(sessionId)
                    .orElseThrow(() -> new ResourceNotFoundException("SimulationRun", sessionId));
            if (run.getStatus() == SimulationStatus.RUNNING) {
                throw new ApiException(ErrorCode.SIMULATION_ALREADY_RUNNING);
            }
            if (run.getStatus() == SimulationStatus.COMPLETED) {
                throw new ApiException(ErrorCode.SIMULATION_INVALID_STATE, "Completed simulation cannot be resumed");
            }
            DeviceAssignment assignment = assignmentRepository.findById(session.getDeviceAssignmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Assignment", session.getDeviceAssignmentId()));
            Device device = deviceRepository.findById(assignment.getDeviceId())
                    .orElseThrow(() -> new ResourceNotFoundException("Device", assignment.getDeviceId()));

            Instant now = Instant.now();
            run.startNextGeneration(now, now.plus(properties.getLeaseDuration()));
            SimulationRun saved = simulationRunRepository.saveAndFlush(run);
            auditService.record(actorId, AuditAction.SIMULATION_RESUMED, "SIMULATION_RUN", saved.getId(),
                    Map.of("sessionId", sessionId.toString(),
                            "resumeFromStep", String.valueOf(saved.getLastStep() + 1L),
                            "workerGeneration", String.valueOf(saved.getWorkerGeneration())));
            return new PreparedDispatch(saved, buildWorkerStartCommand(saved, session, device.getDeviceCode()));
        });

        try {
            WorkerControlResponse ack = workerClient.resumeWorker(prepared.command());
            SimulationRun finalRun = recordWorkerInstanceIfPresent(prepared.run().getId(),
                    prepared.run().getWorkerGeneration(), ack);
            return new SimulationResponse(finalRun);
        } catch (SimulationWorkerException ex) {
            markFailedForGeneration(prepared.run().getId(), prepared.run().getWorkerGeneration());
            throw new ApiException(ErrorCode.SIMULATION_WORKER_UNAVAILABLE, ex.getMessage());
        }
    }

    public SimulationResponse pause(UUID sessionId, UUID actorId) {
        SimulationRun updated = transactionTemplate.execute(tx -> {
            MonitoringSession session = sessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));
            if (!session.isActive()) {
                throw new ApiException(ErrorCode.INVALID_SESSION_TRANSITION,
                        "Cannot pause simulation for " + session.getStatus() + " session " + session.getSessionCode());
            }
            SimulationRun run = simulationRunRepository.findBySessionIdForUpdate(sessionId)
                    .orElseThrow(() -> new ResourceNotFoundException("SimulationRun", sessionId));
            if (run.getStatus() == SimulationStatus.PAUSED) {
                return run;
            }
            if (run.getStatus() != SimulationStatus.RUNNING) {
                throw new ApiException(ErrorCode.SIMULATION_INVALID_STATE,
                        "Cannot pause simulation in status " + run.getStatus());
            }
            run.pause(Instant.now());
            SimulationRun saved = simulationRunRepository.saveAndFlush(run);
            auditService.record(actorId, AuditAction.SIMULATION_PAUSED, "SIMULATION_RUN", saved.getId(),
                    Map.of("sessionId", sessionId.toString(), "lastStep", String.valueOf(saved.getLastStep())));
            return saved;
        });

        bestEffortWorkerPause(updated);
        return new SimulationResponse(updated);
    }

    public SimulationResponse stop(UUID sessionId, UUID actorId) {
        SimulationRun updated = transactionTemplate.execute(tx -> {
            sessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));
            SimulationRun run = simulationRunRepository.findBySessionIdForUpdate(sessionId)
                    .orElseThrow(() -> new ResourceNotFoundException("SimulationRun", sessionId));
            if (run.getStatus() == SimulationStatus.STOPPED) {
                return run;
            }
            run.stop(Instant.now());
            SimulationRun saved = simulationRunRepository.saveAndFlush(run);
            auditService.record(actorId, AuditAction.SIMULATION_STOPPED, "SIMULATION_RUN", saved.getId(),
                    Map.of("sessionId", sessionId.toString(), "lastStep", String.valueOf(saved.getLastStep())));
            return saved;
        });

        bestEffortWorkerStop(updated);
        return new SimulationResponse(updated);
    }

    public SimulationResponse get(UUID sessionId) {
        return transactionTemplate.execute(tx -> {
            if (!sessionRepository.existsById(sessionId)) {
                throw new ResourceNotFoundException("Session", sessionId);
            }
            SimulationRun run = simulationRunRepository.findBySessionId(sessionId)
                    .orElseThrow(() -> new ResourceNotFoundException("SimulationRun", sessionId));
            return new SimulationResponse(run);
        });
    }

    /** Called inside SessionService's end/cancel DB transaction to durably fence off the simulator run. */
    public Optional<SimulationRun> stopInActiveTransaction(UUID sessionId, Instant now) {
        Optional<SimulationRun> runOpt = simulationRunRepository.findBySessionIdForUpdate(sessionId);
        runOpt.ifPresent(run -> {
            if (run.getStatus() != SimulationStatus.STOPPED && run.getStatus() != SimulationStatus.COMPLETED) {
                run.stop(now);
                simulationRunRepository.saveAndFlush(run);
            }
        });
        return runOpt;
    }

    /** Called by SessionService strictly AFTER the DB transaction commits. */
    public void notifyWorkerStoppedAfterSessionFinish(SimulationRun run) {
        if (run != null) {
            bestEffortWorkerStop(run);
        }
    }

    private WorkerStartCommand buildWorkerStartCommand(SimulationRun run, MonitoringSession session, String deviceCode) {
        return new WorkerStartCommand(
                run.getId(),
                session.getId(),
                session.getSessionCode(),
                deviceCode,
                run.getScenarioId(),
                run.getSeed(),
                run.getCadenceHz(),
                run.getPlaybackSpeed(),
                run.getQualityPreset(),
                run.getWorkerGeneration(),
                run.getLastStep() + 1L,
                session.getStartedAt(),
                Math.max(1L, properties.getLeaseDuration().getSeconds()),
                properties.getIngestionBaseUrl()
        );
    }

    private SimulationRun recordWorkerInstanceIfPresent(UUID runId, long expectedGeneration, WorkerControlResponse ack) {
        return transactionTemplate.execute(tx -> {
            SimulationRun locked = simulationRunRepository.findByIdForUpdate(runId)
                    .orElseThrow(() -> new ResourceNotFoundException("SimulationRun", runId));
            if (ack != null && ack.workerInstanceId() != null && locked.getWorkerGeneration() == expectedGeneration
                    && locked.getStatus() == SimulationStatus.RUNNING) {
                locked.attachWorkerInstance(ack.workerInstanceId(), Instant.now());
                return simulationRunRepository.saveAndFlush(locked);
            }
            return locked;
        });
    }

    private void markFailedForGeneration(UUID runId, long expectedGeneration) {
        transactionTemplate.executeWithoutResult(tx -> {
            simulationRunRepository.findByIdForUpdate(runId).ifPresent(locked -> {
                if (locked.getWorkerGeneration() == expectedGeneration
                        && locked.getStatus() == SimulationStatus.RUNNING) {
                    locked.fail(Instant.now());
                    simulationRunRepository.save(locked);
                }
            });
        });
    }

    private void bestEffortWorkerPause(SimulationRun run) {
        try {
            workerClient.pauseWorker(new WorkerStopCommand(run.getSessionId(), run.getId(), run.getWorkerGeneration()));
        } catch (RuntimeException ex) {
            log.warn("Best-effort pause notification failed for session {} (fencing already active): {}",
                    run.getSessionId(), ex.getMessage());
        }
    }

    private void bestEffortWorkerStop(SimulationRun run) {
        try {
            workerClient.stopWorker(new WorkerStopCommand(run.getSessionId(), run.getId(), run.getWorkerGeneration()));
        } catch (RuntimeException ex) {
            log.warn("Best-effort stop notification failed for session {} (fencing already active): {}",
                    run.getSessionId(), ex.getMessage());
        }
    }
}
