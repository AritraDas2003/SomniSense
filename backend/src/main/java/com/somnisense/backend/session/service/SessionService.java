package com.somnisense.backend.session.service;

import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.audit.service.AuditAction;
import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.common.exception.InvalidRequestException;
import com.somnisense.backend.common.exception.ResourceNotFoundException;
import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.entity.EnrollmentStatus;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.entity.PatientStatus;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.prediction.service.InferenceJobService;
import com.somnisense.backend.session.dto.SessionResponse;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.simulation.entity.SimulationRun;
import com.somnisense.backend.simulation.service.SimulationLifecycleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Monitoring session lifecycle (Stage 8, decisions 2026-11-21).
 *
 * Start rules:
 * - The patient must be ACTIVE and have an active device assignment (the backend picks it; an optional
 *   deviceAssignmentId from the client must match it).
 * - The device must still be active and ENROLLED.
 * - One ACTIVE session per patient/assignment. Starting again while one is ACTIVE returns THAT session
 *   (safe double-click). Java checks + V3 partial unique indexes.
 * End/cancel: ACTIVE -> ENDED or CANCELLED only; repeating the same action is a no-op; ENDED<->CANCELLED is 409.
 * startedBy/endedBy and all timestamps come from the server, never from the request.
 *
 * Locking (with AssignmentService): start locks the PATIENT row, then the ASSIGNMENT row. Release/reassign
 * lock the assignment row before checking for an active session. Whoever gets the assignment lock first wins;
 * the other then sees the committed result and answers with a clear 409.
 */
@Service
public class SessionService {

    static final int MAX_CODE_ATTEMPTS = 5;

    private final MonitoringSessionRepository sessionRepository;
    private final DeviceAssignmentRepository assignmentRepository;
    private final PatientRepository patientRepository;
    private final DeviceRepository deviceRepository;
    private final SessionCodeGenerator codeGenerator;
    private final AuditService auditService;
    private final SimulationLifecycleService simulationLifecycleService;
    private final InferenceJobService inferenceJobService;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public SessionService(MonitoringSessionRepository sessionRepository, DeviceAssignmentRepository assignmentRepository,
                          PatientRepository patientRepository, DeviceRepository deviceRepository,
                          SessionCodeGenerator codeGenerator, AuditService auditService,
                          SimulationLifecycleService simulationLifecycleService,
                          InferenceJobService inferenceJobService,
                          PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.assignmentRepository = assignmentRepository;
        this.patientRepository = patientRepository;
        this.deviceRepository = deviceRepository;
        this.codeGenerator = codeGenerator;
        this.auditService = auditService;
        this.simulationLifecycleService = simulationLifecycleService;
        this.inferenceJobService = inferenceJobService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Result of start(): the session plus whether it was newly created (201) or already running (200). */
    public static final class StartResult {
        private final SessionResponse session;
        private final boolean created;

        StartResult(SessionResponse session, boolean created) {
            this.session = session;
            this.created = created;
        }

        public SessionResponse getSession() {
            return session;
        }

        public boolean isCreated() {
            return created;
        }
    }

    // ---------------------------------------------------------------- start

    @Transactional
    public StartResult start(UUID patientId, UUID expectedAssignmentId, UUID actorId) {
        Patient patient = patientRepository.findByIdForUpdate(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", patientId));
        if (patient.getStatus() != PatientStatus.ACTIVE) {
            throw new ApiException(ErrorCode.PATIENT_NOT_ACTIVE);
        }
        // Load the active assignment WITH a row lock in one query, so a concurrent release cannot slip in
        // between (it either finished before - then there is none - or waits until we are done).
        DeviceAssignment assignment = assignmentRepository.findActiveByPatientIdForUpdate(patientId)
                .orElseThrow(() -> new ApiException(ErrorCode.NO_ACTIVE_ASSIGNMENT));
        if (expectedAssignmentId != null && !expectedAssignmentId.equals(assignment.getId())) {
            throw new ApiException(ErrorCode.ASSIGNMENT_MISMATCH);
        }
        Device device = deviceRepository.findById(assignment.getDeviceId()).orElseThrow();
        if (!device.isActive() || device.getEnrollmentStatus() != EnrollmentStatus.ENROLLED) {
            throw new ApiException(ErrorCode.DEVICE_NOT_AVAILABLE, "Device " + device.getDeviceCode()
                    + " must be active and ENROLLED to start monitoring (it is " + device.getEnrollmentStatus() + ")");
        }

        Optional<MonitoringSession> running = sessionRepository.findByPatientIdAndStatus(patientId, SessionStatus.ACTIVE);
        if (running.isPresent()) {
            if (running.get().getDeviceAssignmentId().equals(assignment.getId())) {
                return new StartResult(toResponse(running.get(), patient, device), false);   // double click
            }
            throw new ApiException(ErrorCode.SESSION_ALREADY_ACTIVE);
        }

        Instant now = Instant.now();
        MonitoringSession session = new MonitoringSession(newUniqueCode(now), patientId, assignment.getId(), actorId, now);
        try {
            sessionRepository.saveAndFlush(session);
        } catch (DataIntegrityViolationException raceLost) {
            throw new ApiException(ErrorCode.SESSION_ALREADY_ACTIVE, ErrorCode.SESSION_ALREADY_ACTIVE.getDefaultMessage()
                    + " (started by another request at the same moment; reload)");
        }
        auditService.record(actorId, AuditAction.SESSION_STARTED, "SESSION", session.getId(),
                Map.of("deviceAssignmentId", assignment.getId().toString(), "sourceType", device.getDeviceType().name()));
        return new StartResult(toResponse(session, patient, device), true);
    }

    // ---------------------------------------------------------------- end / cancel

    public SessionResponse end(UUID sessionId, UUID actorId) {
        return finish(sessionId, SessionStatus.ENDED, AuditAction.SESSION_ENDED, actorId);
    }

    public SessionResponse cancel(UUID sessionId, UUID actorId) {
        return finish(sessionId, SessionStatus.CANCELLED, AuditAction.SESSION_CANCELLED, actorId);
    }

    private SessionResponse finish(UUID sessionId, SessionStatus target, String auditAction, UUID actorId) {
        record FinishOutcome(SessionResponse response, SimulationRun stoppedRun) {
        }
        FinishOutcome outcome = transactionTemplate.execute(tx -> {
            MonitoringSession session = sessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));
            SimulationRun stoppedRun = null;
            if (session.isActive()) {
                Instant now = Instant.now();
                session.finish(target, actorId);
                stoppedRun = simulationLifecycleService.stopInActiveTransaction(session.getId(), now).orElse(null);
                if (target == SessionStatus.CANCELLED) {
                    inferenceJobService.cancelOpenRetries(session.getId());
                }
                auditService.record(actorId, auditAction, "SESSION", session.getId(), null);
            } else if (session.getStatus() != target) {
                throw new ApiException(ErrorCode.INVALID_SESSION_TRANSITION, "Session " + session.getSessionCode()
                        + " is already " + session.getStatus() + " and cannot be changed to " + target);
            }
            return new FinishOutcome(toResponse(session), stoppedRun);
        });
        if (outcome != null && outcome.stoppedRun() != null) {
            simulationLifecycleService.notifyWorkerStoppedAfterSessionFinish(outcome.stoppedRun());
        }
        return outcome == null ? null : outcome.response();
    }

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public SessionResponse get(UUID id) {
        return toResponse(sessionRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Session", id)));
    }

    /** Newest first. startedFrom/startedTo filter on started_at (inclusive from, exclusive to). */
    @Transactional(readOnly = true)
    public PageResponse<SessionResponse> list(UUID patientId, SessionStatus status, Instant startedFrom,
                                              Instant startedTo, int page, int size) {
        if (startedFrom != null && startedTo != null && !startedFrom.isBefore(startedTo)) {
            throw new InvalidRequestException("startedFrom must be before startedTo");
        }
        Specification<MonitoringSession> filter = Specification.allOf(
                (root, q, cb) -> patientId == null ? null : cb.equal(root.get("patientId"), patientId),
                (root, q, cb) -> status == null ? null : cb.equal(root.get("status"), status),
                (root, q, cb) -> startedFrom == null ? null : cb.greaterThanOrEqualTo(root.get("startedAt"), startedFrom),
                (root, q, cb) -> startedTo == null ? null : cb.lessThan(root.get("startedAt"), startedTo));
        Page<MonitoringSession> result = sessionRepository.findAll(filter,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt").and(Sort.by("id"))));

        // Patients, assignments and devices for the whole page: three queries in total.
        Map<UUID, Patient> patients = patientRepository.findAllById(
                        result.getContent().stream().map(MonitoringSession::getPatientId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Patient::getId, Function.identity()));
        Map<UUID, DeviceAssignment> assignments = assignmentRepository.findAllById(
                        result.getContent().stream().map(MonitoringSession::getDeviceAssignmentId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(DeviceAssignment::getId, Function.identity()));
        Map<UUID, Device> devices = deviceRepository.findAllById(
                        assignments.values().stream().map(DeviceAssignment::getDeviceId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Device::getId, Function.identity()));
        Instant now = Instant.now();
        return PageResponse.from(result, s -> new SessionResponse(s, patients.get(s.getPatientId()),
                devices.get(assignments.get(s.getDeviceAssignmentId()).getDeviceId()), now));
    }

    // ---------------------------------------------------------------- helpers

    String newUniqueCode(Instant startedAt) {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = codeGenerator.generate(startedAt);
            if (!sessionRepository.existsBySessionCode(code)) {
                return code;
            }
        }
        throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not generate a unique session code");
    }

    private SessionResponse toResponse(MonitoringSession s) {
        Patient patient = patientRepository.findById(s.getPatientId()).orElseThrow();
        DeviceAssignment assignment = assignmentRepository.findById(s.getDeviceAssignmentId()).orElseThrow();
        Device device = deviceRepository.findById(assignment.getDeviceId()).orElseThrow();
        return toResponse(s, patient, device);
    }

    private SessionResponse toResponse(MonitoringSession s, Patient patient, Device device) {
        return new SessionResponse(s, patient, device, Instant.now());
    }
}
