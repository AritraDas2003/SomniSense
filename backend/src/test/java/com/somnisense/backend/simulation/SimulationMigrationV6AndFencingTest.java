package com.somnisense.backend.simulation;

import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.ingestion.dto.IngestReadingItem;
import com.somnisense.backend.ingestion.dto.IngestReadingsRequest;
import com.somnisense.backend.ingestion.dto.IngestionResult;
import com.somnisense.backend.ingestion.service.IngestionService;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.prediction.entity.InferenceJobStatus;
import com.somnisense.backend.prediction.entity.InferenceWindowJob;
import com.somnisense.backend.prediction.repository.InferenceWindowJobRepository;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.session.service.SessionService;
import com.somnisense.backend.simulation.client.SimulationWorkerClient;
import com.somnisense.backend.simulation.config.SimulationProperties;
import com.somnisense.backend.simulation.dto.SimulationResponse;
import com.somnisense.backend.simulation.dto.StartSimulationRequest;
import com.somnisense.backend.simulation.entity.SimulationRun;
import com.somnisense.backend.simulation.entity.SimulationStatus;
import com.somnisense.backend.simulation.repository.SimulationRunRepository;
import com.somnisense.backend.simulation.service.SimulationLifecycleService;
import com.somnisense.backend.support.TestDatabase;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SimulationMigrationV6AndFencingTest {

    private static final String SIM_KEY = "test-internal-sim-key-0123456789abcdef-0123456789";

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private DeviceAssignmentRepository assignmentRepository;
    @Autowired
    private MonitoringSessionRepository sessionRepository;
    @Autowired
    private SimulationRunRepository simulationRunRepository;
    @Autowired
    private InferenceWindowJobRepository jobRepository;
    @Autowired
    private IngestionService ingestionService;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private AuditService auditService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    private HttpServer workerStub;
    private final AtomicInteger stubHttpStatus = new AtomicInteger(200);
    private final AtomicReference<String> lastStartPayload = new AtomicReference<>();
    private final AtomicReference<String> lastResumePayload = new AtomicReference<>();
    private final AtomicReference<String> lastStopPath = new AtomicReference<>();
    private final AtomicReference<String> lastKeyHeader = new AtomicReference<>();

    private SimulationLifecycleService lifecycleService;
    private User admin;
    private Patient simPatient;
    private Device simDevice;
    private MonitoringSession simSession;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(jdbcTemplate);
        stubHttpStatus.set(200);
        lastStartPayload.set(null);
        lastResumePayload.set(null);
        lastStopPath.set(null);

        workerStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workerStub.createContext("/internal/sim/", exchange -> {
            lastKeyHeader.set(exchange.getRequestHeaders().getFirst("X-Internal-Sim-Key"));
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.endsWith("/start")) {
                lastStartPayload.set(body);
            } else if (path.endsWith("/resume")) {
                lastResumePayload.set(body);
            } else if (path.endsWith("/stop") || path.endsWith("/pause")) {
                lastStopPath.set(path);
            }
            int code = stubHttpStatus.get();
            byte[] resp = ("{\"simulationRunId\":\"" + UUID.randomUUID()
                    + "\",\"sessionId\":\"" + UUID.randomUUID()
                    + "\",\"workerInstanceId\":\"worker-stub-1\",\"status\":\"OK\",\"lastSubmittedStep\":0}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        workerStub.start();

        SimulationProperties props = new SimulationProperties();
        props.setWorkerBaseUrl("http://127.0.0.1:" + workerStub.getAddress().getPort());
        props.setInternalKey(SIM_KEY);
        props.setLeaseDuration(Duration.ofSeconds(30));
        props.setConnectTimeout(Duration.ofSeconds(1));
        props.setReadTimeout(Duration.ofSeconds(2));

        SimulationWorkerClient workerClient = new SimulationWorkerClient(props, jsonMapper);
        lifecycleService = new SimulationLifecycleService(
                sessionRepository, assignmentRepository, deviceRepository, simulationRunRepository,
                auditService, props, workerClient, transactionManager);

        admin = userRepository.save(new User("admin4a", "admin4a@test.local",
                passwordEncoder.encode("a-test-password-123"), Role.ADMIN));
        UUID by = admin.getId();
        simPatient = patientRepository.save(new Patient("PT-SIM001", "Simulated Patient", null, null, null,
                SourceType.SIMULATED, by));
        simDevice = deviceRepository.save(new Device("DEV-SIM01", SourceType.SIMULATED, "SimOx", "SN-1", by));
        DeviceAssignment assignment = assignmentRepository.save(new DeviceAssignment(simDevice.getId(), simPatient.getId(), by));
        Instant startedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        simSession = sessionRepository.save(new MonitoringSession("SES-SIM-001", simPatient.getId(),
                assignment.getId(), by, startedAt));
    }

    @AfterEach
    void tearDown() {
        if (workerStub != null) {
            workerStub.stop(0);
        }
    }

    @Test
    void enforcesAtomicWorkerGenerationFencingAndContiguousLastStepCheckpoint() {
        StartSimulationRequest startReq = new StartSimulationRequest();
        startReq.setScenarioId("normal_sleep");
        startReq.setSeed(12345L);
        SimulationLifecycleService.StartResult started = lifecycleService.start(simSession.getId(), startReq, admin.getId());
        assertThat(started.created()).isTrue();
        SimulationResponse sim = started.simulation();
        assertThat(sim.status()).isEqualTo(SimulationStatus.RUNNING);
        assertThat(sim.workerGeneration()).isEqualTo(1L);
        assertThat(sim.workerInstanceId()).isEqualTo("worker-stub-1");
        assertThat(lastKeyHeader.get()).isEqualTo(SIM_KEY);
        assertThat(lastStartPayload.get()).contains("\"startStep\":1");

        // Missing fencing fails with 409 SIMULATION_FENCING_REQUIRED
        IngestReadingsRequest unfenced = buildRequest(simDevice.getDeviceCode(), simSession.getSessionCode(),
                null, null, List.of(reading(1, simSession.getStartedAt().plusSeconds(1))));
        assertThatThrownBy(() -> ingestionService.ingest(unfenced))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SIMULATION_FENCING_REQUIRED);

        // Wrong generation fails with 409 STALE_WORKER_GENERATION
        IngestReadingsRequest wrongGen = buildRequest(simDevice.getDeviceCode(), simSession.getSessionCode(),
                sim.id(), 99L, List.of(reading(1, simSession.getStartedAt().plusSeconds(1))));
        assertThatThrownBy(() -> ingestionService.ingest(wrongGen))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.STALE_WORKER_GENERATION);

        // Step 1, 2 + gap (skip 3) + step 4 -> contiguous checkpoint advances ONLY to 2!
        IngestReadingsRequest gapBatch = buildRequest(simDevice.getDeviceCode(), simSession.getSessionCode(),
                sim.id(), 1L, List.of(
                        reading(1, simSession.getStartedAt().plusSeconds(1)),
                        reading(2, simSession.getStartedAt().plusSeconds(2)),
                        reading(4, simSession.getStartedAt().plusSeconds(4))
                ));
        IngestionResult res1 = ingestionService.ingest(gapBatch);
        assertThat(res1.getAccepted()).isEqualTo(3);

        SimulationRun afterGap = simulationRunRepository.findById(sim.id()).orElseThrow();
        assertThat(afterGap.getLastStep()).isEqualTo(2L);

        // Duplicate batch of steps [1, 2] must NOT advance lastStep past 2
        IngestionResult dupRes = ingestionService.ingest(buildRequest(simDevice.getDeviceCode(), simSession.getSessionCode(),
                sim.id(), 1L, List.of(
                        reading(1, simSession.getStartedAt().plusSeconds(1)),
                        reading(2, simSession.getStartedAt().plusSeconds(2))
                )));
        assertThat(dupRes.getAccepted()).isZero();
        assertThat(dupRes.getDuplicates()).isEqualTo(2);
        assertThat(simulationRunRepository.findById(sim.id()).orElseThrow().getLastStep()).isEqualTo(2L);

        // Pause prevents ingestion from generation 1
        SimulationResponse paused = lifecycleService.pause(simSession.getId(), admin.getId());
        assertThat(paused.status()).isEqualTo(SimulationStatus.PAUSED);
        assertThat(paused.leaseExpiresAt()).isNull();

        assertThatThrownBy(() -> ingestionService.ingest(buildRequest(simDevice.getDeviceCode(), simSession.getSessionCode(),
                sim.id(), 1L, List.of(reading(3, simSession.getStartedAt().plusSeconds(3))))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SIMULATION_NOT_RUNNING);

        // Resume increments workerGeneration -> 2 and dispatches startStep = lastStep + 1 (= 3)
        SimulationResponse resumed = lifecycleService.resume(simSession.getId(), admin.getId());
        assertThat(resumed.status()).isEqualTo(SimulationStatus.RUNNING);
        assertThat(resumed.workerGeneration()).isEqualTo(2L);
        assertThat(lastResumePayload.get()).contains("\"startStep\":3");

        // Orphan worker with generation 1 is rejected with STALE_WORKER_GENERATION
        assertThatThrownBy(() -> ingestionService.ingest(buildRequest(simDevice.getDeviceCode(), simSession.getSessionCode(),
                sim.id(), 1L, List.of(reading(3, simSession.getStartedAt().plusSeconds(3))))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.STALE_WORKER_GENERATION);

        // Generation 2 filling missing step 3 bridges contiguous gap [3, 4] -> lastStep becomes 4!
        IngestionResult fillGap = ingestionService.ingest(buildRequest(simDevice.getDeviceCode(), simSession.getSessionCode(),
                sim.id(), 2L, List.of(reading(3, simSession.getStartedAt().plusSeconds(3)))));
        assertThat(fillGap.getAccepted()).isEqualTo(1);
        assertThat(simulationRunRepository.findById(sim.id()).orElseThrow().getLastStep()).isEqualTo(4L);
    }

    @Test
    void startupRecoveryPausesRunningRunsAndExpiredLeaseRejectsIngestion() {
        SimulationRun activeRun = simulationRunRepository.save(new SimulationRun(
                simSession.getId(), "normal_sleep", 7L,
                new BigDecimal("1.00"), new BigDecimal("1.00"), null,
                SimulationStatus.RUNNING, 10L, 1L, Instant.now().plusSeconds(30), "w-1",
                Instant.now().minusSeconds(10)));

        lifecycleService.recoverRunningSimulationsOnStartup();
        SimulationRun recovered = simulationRunRepository.findById(activeRun.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(SimulationStatus.PAUSED);
        assertThat(recovered.getLeaseExpiresAt()).isNull();
        assertThat(recovered.getWorkerGeneration()).isEqualTo(1L);

        // Also verify expired lease in DB rejects ingestion with SIMULATION_LEASE_EXPIRED
        SimulationRun expiredRun = recovered;
        expiredRun.startNextGeneration(Instant.now(), Instant.now().minusSeconds(1));
        simulationRunRepository.save(expiredRun);

        assertThatThrownBy(() -> ingestionService.ingest(buildRequest(simDevice.getDeviceCode(), simSession.getSessionCode(),
                expiredRun.getId(), 2L, List.of(reading(11, simSession.getStartedAt().plusSeconds(11))))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SIMULATION_LEASE_EXPIRED);

        assertThat(lifecycleService.expireStaleLeases()).isEqualTo(1);
        assertThat(simulationRunRepository.findById(expiredRun.getId()).orElseThrow().getStatus())
                .isEqualTo(SimulationStatus.PAUSED);
    }

    @Test
    void workerDispatchFailureMarksRunFailedAndCancelTerminatesSimulationAndOpenRetries() {
        // Dispatch failure on /start compensates RUNNING -> FAILED instead of leaving orphan lease
        stubHttpStatus.set(500);
        assertThatThrownBy(() -> lifecycleService.start(simSession.getId(), new StartSimulationRequest(), admin.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SIMULATION_WORKER_UNAVAILABLE);

        SimulationRun failed = simulationRunRepository.findBySessionId(simSession.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(SimulationStatus.FAILED);
        assertThat(failed.getLeaseExpiresAt()).isNull();

        // Worker recovers -> resume succeeds -> add RETRY_WAIT job -> session cancel cleans both up
        stubHttpStatus.set(200);
        SimulationResponse resumed = lifecycleService.resume(simSession.getId(), admin.getId());
        assertThat(resumed.status()).isEqualTo(SimulationStatus.RUNNING);

        Instant now = Instant.now();
        InferenceWindowJob openJob = jobRepository.save(new InferenceWindowJob(
                simSession.getId(), "phase7_model_c:1.0.0",
                simSession.getStartedAt(), simSession.getStartedAt().plusSeconds(60),
                InferenceJobStatus.RETRY_WAIT, 1, now.plusSeconds(10), "transient error", now));

        sessionService.cancel(simSession.getId(), admin.getId());

        assertThat(simulationRunRepository.findById(resumed.id()).orElseThrow().getStatus())
                .isEqualTo(SimulationStatus.STOPPED);
        assertThat(jobRepository.findById(openJob.getId()).orElseThrow().getStatus())
                .isEqualTo(InferenceJobStatus.CANCELLED);
    }

    @Test
    void preservesBackwardCompatibilityForPhysicalDevicesAndUnrunSimulatedSessions() {
        // Simulated session WITHOUT a SimulationRun row still accepts existing Stage 9 batches
        IngestionResult simNoRun = ingestionService.ingest(buildRequest(simDevice.getDeviceCode(), simSession.getSessionCode(),
                null, null, List.of(reading(1, simSession.getStartedAt().plusSeconds(1)))));
        assertThat(simNoRun.getAccepted()).isEqualTo(1);

        // Physical device accepts normal batch and rejects simulator control/fencing
        UUID by = admin.getId();
        Patient physPatient = patientRepository.save(new Patient("PT-PHY001", "Physical Patient", null, null, null,
                SourceType.PHYSICAL, by));
        Device physDevice = deviceRepository.save(new Device("DEV-PHY01", SourceType.PHYSICAL, "OxReal", "SN-P", by));
        physDevice.changeEnrollment(com.somnisense.backend.device.entity.EnrollmentStatus.ENROLLED);
        deviceRepository.save(physDevice);
        DeviceAssignment physAssign = assignmentRepository.save(new DeviceAssignment(physDevice.getId(), physPatient.getId(), by));
        MonitoringSession physSession = sessionRepository.save(new MonitoringSession("SES-PHY-001",
                physPatient.getId(), physAssign.getId(), by, Instant.now().minusSeconds(60)));

        IngestionResult physOk = ingestionService.ingest(buildRequest(physDevice.getDeviceCode(), physSession.getSessionCode(),
                null, null, List.of(reading(1, physSession.getStartedAt().plusSeconds(1)))));
        assertThat(physOk.getAccepted()).isEqualTo(1);

        assertThatThrownBy(() -> lifecycleService.start(physSession.getId(), new StartSimulationRequest(), by))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SIMULATION_DEVICE_NOT_SIMULATED);
    }

    @Test
    void verifiesFlywayV1ThroughV6HistoryConstraintsAndBackfillSemantics() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");

        List<String> simConstraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'simulation_runs'::regclass ORDER BY conname",
                String.class);
        assertThat(simConstraints).contains(
                "chk_simulation_runs_generation_non_negative",
                "chk_simulation_runs_running_has_lease"
        );

        List<String> jobConstraints = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'inference_window_jobs'::regclass ORDER BY conname",
                String.class);
        assertThat(jobConstraints).contains(
                "chk_inference_jobs_window",
                "chk_inference_jobs_status",
                "chk_inference_jobs_attempts",
                "chk_inference_jobs_updated"
        );

        // Database constraint must reject RUNNING simulation_runs rows with lease_expires_at IS NULL
        Instant now = Instant.now();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO simulation_runs (id, session_id, scenario_id, seed, cadence_hz, playback_speed, "
                        + "status, last_step, started_at, updated_at, worker_generation, lease_expires_at, worker_instance_id) "
                        + "VALUES (?, ?, 'normal_sleep', 1, 1.00, 1.00, 'RUNNING', 0, ?, ?, 1, NULL, 'orphan')",
                UUID.randomUUID(), simSession.getId(), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Verify V6 backfill SQL behavior on a seeded pre-V6 temporary table mimicking a RUNNING row without a lease
        jdbcTemplate.execute("CREATE TEMP TABLE tmp_v5_sim_runs ("
                + "id uuid PRIMARY KEY, session_id uuid NOT NULL, status varchar(20) NOT NULL, "
                + "started_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, "
                + "worker_generation bigint NOT NULL DEFAULT 0, lease_expires_at timestamptz, worker_instance_id varchar(100))");
        try {
            UUID rowId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO tmp_v5_sim_runs VALUES (?, ?, 'RUNNING', ?, ?, 0, ?, 'old-worker')",
                    rowId, simSession.getId(), java.sql.Timestamp.from(now.minusSeconds(30)),
                    java.sql.Timestamp.from(now.minusSeconds(10)), java.sql.Timestamp.from(now.plusSeconds(20)));
            int backfilled = jdbcTemplate.update(
                    "UPDATE tmp_v5_sim_runs SET status = 'PAUSED', lease_expires_at = NULL, "
                            + "worker_instance_id = NULL, updated_at = GREATEST(started_at, now()) WHERE status = 'RUNNING'");
            assertThat(backfilled).isEqualTo(1);
            var row = jdbcTemplate.queryForMap("SELECT status, lease_expires_at, worker_instance_id FROM tmp_v5_sim_runs WHERE id = ?", rowId);
            assertThat(row.get("status")).isEqualTo("PAUSED");
            assertThat(row.get("lease_expires_at")).isNull();
            assertThat(row.get("worker_instance_id")).isNull();
            jdbcTemplate.execute("ALTER TABLE tmp_v5_sim_runs ADD CONSTRAINT chk_tmp_running_has_lease CHECK (status <> 'RUNNING' OR lease_expires_at IS NOT NULL)");
        } finally {
            jdbcTemplate.execute("DROP TABLE IF EXISTS tmp_v5_sim_runs");
        }
    }

    private IngestReadingItem reading(long step, Instant measuredAt) {
        IngestReadingItem item = new IngestReadingItem();
        item.setReadingKey(String.format("%s-%s-%08d", simDevice.getDeviceCode(), simSession.getSessionCode(), step));
        item.setMeasuredAt(measuredAt);
        item.setSpo2(new BigDecimal("96.50"));
        item.setHeartRate(new BigDecimal("70.00"));
        return item;
    }

    private static IngestReadingsRequest buildRequest(String deviceCode, String sessionCode,
                                                      UUID runId, Long gen, List<IngestReadingItem> items) {
        IngestReadingsRequest req = new IngestReadingsRequest();
        req.setDeviceCode(deviceCode);
        req.setSessionCode(sessionCode);
        req.setSimulationRunId(runId);
        req.setWorkerGeneration(gen);
        req.setWorkerInstanceId("worker-stub-1");
        req.setReadings(items);
        return req;
    }
}
