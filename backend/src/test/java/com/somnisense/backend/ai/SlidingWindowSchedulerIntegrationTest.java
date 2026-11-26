package com.somnisense.backend.ai;

import com.jayway.jsonpath.JsonPath;
import com.somnisense.backend.ai.client.AiSidecarClient;
import com.somnisense.backend.ai.config.AiProperties;
import com.somnisense.backend.ai.config.AiSchedulerProperties;
import com.somnisense.backend.ai.service.SlidingWindowScheduler;
import com.somnisense.backend.ai.service.WindowEvaluationService;
import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.prediction.dto.LatestPredictionResponse;
import com.somnisense.backend.prediction.dto.RecordPredictionCommand;
import com.somnisense.backend.prediction.entity.InferenceJobStatus;
import com.somnisense.backend.prediction.entity.InferenceWindowJob;
import com.somnisense.backend.prediction.entity.InputQualityStatus;
import com.somnisense.backend.prediction.entity.PredictedClass;
import com.somnisense.backend.prediction.entity.Prediction;
import com.somnisense.backend.prediction.entity.PredictionStatus;
import com.somnisense.backend.prediction.repository.InferenceWindowJobRepository;
import com.somnisense.backend.prediction.repository.PredictionRepository;
import com.somnisense.backend.prediction.service.InferenceJobService;
import com.somnisense.backend.prediction.service.PredictionService;
import com.somnisense.backend.reading.entity.SensorReading;
import com.somnisense.backend.reading.repository.SensorReadingRepository;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.session.service.SessionService;
import com.somnisense.backend.simulation.service.SimulationLifecycleService;
import com.somnisense.backend.support.TestDatabase;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SlidingWindowSchedulerIntegrationTest {

    private static final String AI_KEY = "test-internal-ai-key-0123456789abcdef-0123456789";
    private static final String MODEL_VERSION = "phase7_model_c:1.0.0";

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
    private SensorReadingRepository readingRepository;
    @Autowired
    private PredictionRepository predictionRepository;
    @Autowired
    private InferenceWindowJobRepository jobRepository;
    @Autowired
    private PredictionService predictionService;
    @Autowired
    private InferenceJobService inferenceJobService;
    @Autowired
    private SimulationLifecycleService simulationLifecycleService;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JsonMapper jsonMapper;

    private record RecordedAiCall(UUID sessionId, String windowStart, String windowEnd,
                                  String contextStart, int sampleCount) {
    }

    private record StubReply(int status, String body, long delayMs) {
    }

    private HttpServer server;
    private int serverPort;
    private Function<String, StubReply> handler;
    private final List<RecordedAiCall> recordedCalls = new CopyOnWriteArrayList<>();

    private User admin;
    private SlidingWindowScheduler scheduler;
    private AiProperties aiProperties;
    private AiSchedulerProperties schedulerProperties;
    private WindowEvaluationService windowEvaluationService;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(jdbcTemplate);
        recordedCalls.clear();
        handler = req -> new StubReply(200, completedNormalJson(), 0L);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/ai/evaluate-window", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            UUID sId = UUID.fromString(JsonPath.read(reqBody, "$.sessionId"));
            String wStart = JsonPath.read(reqBody, "$.windowStart");
            String wEnd = JsonPath.read(reqBody, "$.windowEnd");
            String cStart = JsonPath.read(reqBody, "$.contextStart");
            List<?> samples = JsonPath.read(reqBody, "$.readings");
            recordedCalls.add(new RecordedAiCall(sId, wStart, wEnd, cStart, samples.size()));

            StubReply reply = handler.apply(reqBody);
            if (reply.delayMs() > 0) {
                try {
                    Thread.sleep(reply.delayMs());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        serverPort = server.getAddress().getPort();

        aiProperties = new AiProperties("http://127.0.0.1:" + serverPort, AI_KEY,
                Duration.ofSeconds(1), Duration.ofMillis(400), MODEL_VERSION);
        schedulerProperties = new AiSchedulerProperties();
        schedulerProperties.setEnabled(false);
        schedulerProperties.setGraceLag(Duration.ofSeconds(3));
        schedulerProperties.setMaxWindowsPerSession(6);
        schedulerProperties.setWorkerThreads(3);

        scheduler = newScheduler(aiProperties);
        admin = userRepository.save(new User("admin4b", "admin4b@test.local",
                passwordEncoder.encode("a-test-password-123"), Role.ADMIN));
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private SlidingWindowScheduler newScheduler(AiProperties props) {
        AiSidecarClient client = new AiSidecarClient(props, jsonMapper);
        windowEvaluationService = new WindowEvaluationService(
                sessionRepository, readingRepository, client, predictionService, props, schedulerProperties);
        return new SlidingWindowScheduler(
                sessionRepository,
                predictionRepository,
                jobRepository,
                inferenceJobService,
                windowEvaluationService,
                simulationLifecycleService,
                props,
                schedulerProperties
        );
    }

    @Test
    void evaluatesFirstWindowWithGraceLagThenSlidesTenSecondsWithCausalContextAndNoDuplicates() {
        Instant startedAt = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MILLIS);
        MonitoringSession session = createSession("PT-4B-01", "DEV-4B-01", "SES-4B-01", startedAt);

        // Insert readings including one right before startedAt and one after window 0 [0,60) -> only causal [end-360, end) included
        Device device = deviceRepository.findByDeviceCode("DEV-4B-01").orElseThrow();
        readingRepository.save(new SensorReading(session.getId(), device.getId(), "k-0",
                new BigDecimal("96.00"), new BigDecimal("68.00"), null, startedAt.plusSeconds(10), startedAt.plusSeconds(11)));
        readingRepository.save(new SensorReading(session.getId(), device.getId(), "k-1",
                new BigDecimal("95.00"), new BigDecimal("70.00"), null, startedAt.plusSeconds(65), startedAt.plusSeconds(66)));

        // Within 3s grace lag for window 0 (windowEnd = startedAt + 60s): 62s < 63s -> must NOT fire
        var tooEarly = scheduler.tickAt(startedAt.plusSeconds(62));
        assertThat(tooEarly.windowsEvaluated()).isZero();
        assertThat(recordedCalls).isEmpty();

        // At startedAt + 63s -> window 0 [0s, 60s) fires with context [end - 360s, end)
        var first = scheduler.tickAt(startedAt.plusSeconds(63));
        assertThat(first.windowsEvaluated()).isEqualTo(1);
        assertThat(recordedCalls).hasSize(1);
        RecordedAiCall call0 = recordedCalls.get(0);
        assertThat(Instant.parse(call0.windowStart())).isEqualTo(startedAt);
        assertThat(Instant.parse(call0.windowEnd())).isEqualTo(startedAt.plusSeconds(60));
        assertThat(Instant.parse(call0.contextStart())).isEqualTo(startedAt.plusSeconds(60).minusSeconds(360));
        assertThat(call0.sampleCount()).isEqualTo(1); // only k-0 inside [0, 60)

        // Repeat tick at 72s (window 1 ends at 70s, needs 73s grace lag) -> duplicate prevention, 0 calls
        assertThat(scheduler.tickAt(startedAt.plusSeconds(72)).windowsEvaluated()).isZero();
        assertThat(recordedCalls).hasSize(1);

        // At 73s -> window 1 [10s, 70s) evaluates with both k-0 and k-1 inside [end - 360s, end)
        assertThat(scheduler.tickAt(startedAt.plusSeconds(73)).windowsEvaluated()).isEqualTo(1);
        assertThat(recordedCalls).hasSize(2);
        RecordedAiCall call1 = recordedCalls.get(1);
        assertThat(Instant.parse(call1.windowStart())).isEqualTo(startedAt.plusSeconds(10));
        assertThat(Instant.parse(call1.windowEnd())).isEqualTo(startedAt.plusSeconds(70));
        assertThat(Instant.parse(call1.contextStart())).isEqualTo(startedAt.plusSeconds(70).minusSeconds(360));
        assertThat(call1.sampleCount()).isEqualTo(2);

        // Prediction history & latest APIs work cleanly with persisted scheduled outputs
        var page = predictionService.list(session.getId(), null, null, 0, 20);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getWindowEnd()).isEqualTo(startedAt.plusSeconds(70));
        LatestPredictionResponse latest = predictionService.latest(session.getId());
        assertThat(latest.isHasPrediction()).isTrue();
        assertThat(latest.getLatest().getWindowEnd()).isEqualTo(startedAt.plusSeconds(70));
    }

    @Test
    void enforcesSixWindowCapAndRoundRobinFairnessAcrossThreeSessions() {
        Instant startedAt = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MILLIS);
        MonitoringSession s1 = createSession("PT-RR-01", "DEV-RR-01", "SES-RR-01", startedAt);
        MonitoringSession s2 = createSession("PT-RR-02", "DEV-RR-02", "SES-RR-02", startedAt);
        MonitoringSession s3 = createSession("PT-RR-03", "DEV-RR-03", "SES-RR-03", startedAt);

        // At t0 + 200s (graceCutoff = 197s), windows 0..13 (end = 60..190) = 14 windows eligible per session
        Instant now = startedAt.plusSeconds(200);

        // Tick 1 caps each session at 6 windows -> 18 evaluated total across 3 sessions
        var tick1 = scheduler.tickAt(now);
        assertThat(tick1.windowsEvaluated()).isEqualTo(18);
        assertThat(recordedCalls).hasSize(18);

        // Check round-robin round ordering: first 3 calls contain all 3 sessions for window 0 (t+60s) before any window 1
        List<Instant> firstThreeEnds = recordedCalls.subList(0, 3).stream()
                .map(c -> Instant.parse(c.windowEnd())).toList();
        assertThat(firstThreeEnds).containsOnly(startedAt.plusSeconds(60));
        List<UUID> firstThreeSessions = recordedCalls.subList(0, 3).stream()
                .map(RecordedAiCall::sessionId).toList();
        assertThat(firstThreeSessions).containsExactlyInAnyOrder(s1.getId(), s2.getId(), s3.getId());

        // Check chronological progression within each session (w0..w5 strictly ascending)
        for (UUID sid : List.of(s1.getId(), s2.getId(), s3.getId())) {
            List<Prediction> preds = predictionRepository.findWindow(
                    sid, Instant.EPOCH, now, PageRequest.of(0, 50)).getContent();
            assertThat(preds).hasSize(6);
            List<Instant> sortedEnds = preds.stream().map(Prediction::getWindowEnd).sorted().toList();
            assertThat(sortedEnds).containsExactly(
                    startedAt.plusSeconds(60), startedAt.plusSeconds(70), startedAt.plusSeconds(80),
                    startedAt.plusSeconds(90), startedAt.plusSeconds(100), startedAt.plusSeconds(110));
        }

        // Tick 2 advances next 6 per session (18); Tick 3 evaluates remaining 2 per session (6)
        assertThat(scheduler.tickAt(now).windowsEvaluated()).isEqualTo(18);
        assertThat(scheduler.tickAt(now).windowsEvaluated()).isEqualTo(6);
        assertThat(scheduler.tickAt(now).windowsEvaluated()).isZero();
    }

    @Test
    void enforcesEndedCompleteWindowsGraceLagNoIncompleteTrailingAndCancelledCleanup() {
        // ENDED session spanning 125 seconds: complete windows end at 60..120 ONLY; trailing [70, 130) never runs
        Instant endedNow = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant startedAt125 = endedNow.minusSeconds(125);
        MonitoringSession endedSession = createSession("PT-END-01", "DEV-END-01", "SES-END-01", startedAt125);

        // Short < 60s ended session must never produce any predictions
        Instant startedAt45 = endedNow.minusSeconds(45);
        MonitoringSession shortEnded = createSession("PT-END-02", "DEV-END-02", "SES-END-02", startedAt45);

        // Inject a transient failure on window 0 [0,60) before ending endedSession
        handler = req -> new StubReply(503, "{\"detail\":\"busy\"}", 0L);
        assertThat(scheduler.tickAt(startedAt125.plusSeconds(63)).failuresRecorded()).isEqualTo(1);
        assertThat(jobRepository.findBySessionIdAndStatus(endedSession.getId(), InferenceJobStatus.RETRY_WAIT)).hasSize(1);

        sessionService.end(endedSession.getId(), admin.getId());
        sessionService.end(shortEnded.getId(), admin.getId());
        MonitoringSession reloadedEnded = sessionRepository.findById(endedSession.getId()).orElseThrow();
        Instant actualEndedAt = reloadedEnded.getEndedAt();

        // While now = actualEndedAt + 1s (grace cutoff = actualEndedAt - 2s), windows ending > actualEndedAt - 2s wait for grace lag!
        handler = req -> new StubReply(200, completedNormalJson(), 0L);
        scheduler.tickAt(actualEndedAt.plusSeconds(1));
        List<Prediction> beforeGrace = predictionRepository.findWindow(
                endedSession.getId(), Instant.EPOCH, actualEndedAt.plusSeconds(60), PageRequest.of(0, 50)).getContent();
        assertThat(beforeGrace).allMatch(p -> !p.getWindowEnd().isAfter(actualEndedAt.minusSeconds(2)));

        // Once grace lag passes (actualEndedAt + 5s), complete windows up to <= actualEndedAt AND due RETRY_WAIT finish cleanly
        scheduler.tickAt(actualEndedAt.plusSeconds(5));
        scheduler.tickAt(actualEndedAt.plusSeconds(5)); // second tick in case >6 catch-up windows existed
        List<Prediction> endedPreds = predictionRepository.findWindow(
                endedSession.getId(), Instant.EPOCH, actualEndedAt.plusSeconds(120), PageRequest.of(0, 50)).getContent();
        assertThat(endedPreds).hasSize(7); // 60, 70, 80, 90, 100, 110, 120
        assertThat(endedPreds).allMatch(p -> !p.getWindowEnd().isAfter(actualEndedAt));
        assertThat(jobRepository.findBySessionIdAndStatus(endedSession.getId(), InferenceJobStatus.RETRY_WAIT)).isEmpty();
        assertThat(jobRepository.findBySessionIdAndStatus(endedSession.getId(), InferenceJobStatus.SUCCEEDED)).hasSize(1);

        // Short < 60s ended session never generated any prediction
        assertThat(predictionRepository.findWindow(
                shortEnded.getId(), Instant.EPOCH, actualEndedAt.plusSeconds(120), PageRequest.of(0, 20)).getContent()).isEmpty();

        // CANCELLED session stops evaluations and cancels open retries immediately without creating predictions
        Instant cancelStart = Instant.now().minusSeconds(200).truncatedTo(ChronoUnit.MILLIS);
        MonitoringSession cancelled = createSession("PT-CAN-01", "DEV-CAN-01", "SES-CAN-01", cancelStart);
        handler = req -> new StubReply(500, "{\"detail\":\"down\"}", 0L);
        scheduler.tickAt(cancelStart.plusSeconds(63));
        assertThat(jobRepository.findBySessionIdAndStatus(cancelled.getId(), InferenceJobStatus.RETRY_WAIT)).hasSize(1);

        sessionService.cancel(cancelled.getId(), admin.getId());
        handler = req -> new StubReply(200, completedNormalJson(), 0L);
        scheduler.tickAt(cancelStart.plusSeconds(200));

        assertThat(jobRepository.findBySessionIdAndStatus(cancelled.getId(), InferenceJobStatus.RETRY_WAIT)).isEmpty();
        assertThat(jobRepository.findBySessionIdAndStatus(cancelled.getId(), InferenceJobStatus.CANCELLED)).hasSize(1);
        assertThat(predictionRepository.findWindow(
                cancelled.getId(), Instant.EPOCH, cancelStart.plusSeconds(500), PageRequest.of(0, 50)).getContent()).isEmpty();
    }

    @Test
    void handlesFullRetryMatrixExhaustionNonRetryableErrorsAndQualityRejectionsDeterministically() throws Exception {
        Instant startedAt = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MILLIS);
        MonitoringSession session = createSession("PT-RTY-01", "DEV-RTY-01", "SES-RTY-01", startedAt);

        // Window 0 (t+60): Attempt 1 -> 503 RETRY_WAIT (+10s)
        handler = req -> new StubReply(503, "{\"detail\":\"unavailable\"}", 0L);
        scheduler.tickAt(startedAt.plusSeconds(63));
        InferenceWindowJob j0 = jobRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                session.getId(), MODEL_VERSION, startedAt, startedAt.plusSeconds(60)).orElseThrow();
        assertThat(j0.getStatus()).isEqualTo(InferenceJobStatus.RETRY_WAIT);
        assertThat(j0.getAttemptCount()).isEqualTo(1);
        assertThat(j0.getNextRetryAt()).isEqualTo(startedAt.plusSeconds(63 + 10));
        assertThat(predictionRepository.findAll()).isEmpty();

        // At t+72s (9s elapsed, before +10s due): Window 0 retry MUST NOT run yet (only forward checks before 73s -> none)
        scheduler.tickAt(startedAt.plusSeconds(72));
        j0 = jobRepository.findById(j0.getId()).orElseThrow();
        assertThat(j0.getAttemptCount()).isEqualTo(1);

        // At t+73s (+10s): Window 0 Attempt 2 hits read timeout (>400ms), Window 1 (t+70) hits HTTP 429!
        handler = req -> {
            String wEnd = JsonPath.read(req, "$.windowEnd");
            if (Instant.parse(wEnd).equals(startedAt.plusSeconds(60))) {
                return new StubReply(200, completedNormalJson(), 650L); // timeout!
            }
            return new StubReply(429, "{\"detail\":\"rate limit\"}", 0L);
        };
        scheduler.tickAt(startedAt.plusSeconds(73));
        j0 = jobRepository.findById(j0.getId()).orElseThrow();
        assertThat(j0.getAttemptCount()).isEqualTo(2);
        assertThat(j0.getStatus()).isEqualTo(InferenceJobStatus.RETRY_WAIT);
        assertThat(j0.getNextRetryAt()).isEqualTo(startedAt.plusSeconds(73 + 30)); // +30s backoff!

        InferenceWindowJob j1 = jobRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                session.getId(), MODEL_VERSION, startedAt.plusSeconds(10), startedAt.plusSeconds(70)).orElseThrow();
        assertThat(j1.getStatus()).isEqualTo(InferenceJobStatus.RETRY_WAIT);
        assertThat(j1.getNextRetryAt()).isEqualTo(startedAt.plusSeconds(73 + 10));

        // At t+103s: Window 0 Attempt 3 against unreachable closed port (connection refused), Window 1 recovers -> 200 COMPLETED
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            closedPort = ss.getLocalPort();
        }
        AiProperties downProps = new AiProperties("http://127.0.0.1:" + closedPort, AI_KEY,
                Duration.ofMillis(300), Duration.ofMillis(300), MODEL_VERSION);
        SlidingWindowScheduler downScheduler = newScheduler(downProps);
        try {
            // Evaluate ONLY Window 0 & forward -> Window 0 attempt 3 fails with ConnectException -> +90s backoff!
            // First let Window 1 succeed via normal server at t+83s so we also test retry -> success transition cleanly!
            handler = req -> new StubReply(200, completedNormalJson(), 0L);
            scheduler.tickAt(startedAt.plusSeconds(83));
            assertThat(jobRepository.findById(j1.getId()).orElseThrow().getStatus()).isEqualTo(InferenceJobStatus.SUCCEEDED);
            assertThat(predictionRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                    session.getId(), MODEL_VERSION, startedAt.plusSeconds(10), startedAt.plusSeconds(70))
                    .orElseThrow().getPredictionStatus()).isEqualTo(PredictionStatus.COMPLETED);

            // Advance Window 3..4 temporarily skipped? Cursor moved cleanly past Window 2 (t+80) at t+83 -> now stop forward by testing at t+103 with 500 or downScheduler!
            // Actually at t+103s downScheduler handles Window 0 Attempt 3 (due at 103s) AND Window 3 (t+90) + Window 4 (t+100).
            downScheduler.tickAt(startedAt.plusSeconds(103));
        } finally {
            downScheduler.shutdown();
        }
        j0 = jobRepository.findById(j0.getId()).orElseThrow();
        assertThat(j0.getAttemptCount()).isEqualTo(3);
        assertThat(j0.getStatus()).isEqualTo(InferenceJobStatus.RETRY_WAIT);
        assertThat(j0.getNextRetryAt()).isEqualTo(startedAt.plusSeconds(103 + 90)); // +90s backoff!

        // At t+193s: Window 0 Attempt 4 hits HTTP 500 -> FAILED_EXHAUSTED + single terminal ERROR prediction!
        handler = req -> new StubReply(500, "{\"detail\":\"internal error\"}", 0L);
        scheduler.tickAt(startedAt.plusSeconds(193));
        j0 = jobRepository.findById(j0.getId()).orElseThrow();
        assertThat(j0.getAttemptCount()).isEqualTo(4);
        assertThat(j0.getStatus()).isEqualTo(InferenceJobStatus.FAILED_EXHAUSTED);
        assertThat(j0.getNextRetryAt()).isNull();
        Prediction p0 = predictionRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                session.getId(), MODEL_VERSION, startedAt, startedAt.plusSeconds(60)).orElseThrow();
        assertThat(p0.getPredictionStatus()).isEqualTo(PredictionStatus.ERROR);
        assertThat(p0.getPredictedClass()).isNull();
        assertThat(p0.getConfidenceScore()).isNull();

        // Non-retryable 400, 401, 403, 422 on separate sessions -> immediate ERROR + FAILED_EXHAUSTED (attemptCount = 1)
        int idx = 10;
        for (int badStatus : List.of(400, 401, 403, 422)) {
            Instant sStart = startedAt.plusSeconds(idx * 10L);
            MonitoringSession badSess = createSession("PT-NR-" + idx, "DEV-NR-" + idx, "SES-NR-" + idx, sStart);
            handler = req -> new StubReply(badStatus, "{\"detail\":\"bad\"}", 0L);
            scheduler.tickAt(sStart.plusSeconds(63));
            InferenceWindowJob badJob = jobRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                    badSess.getId(), MODEL_VERSION, sStart, sStart.plusSeconds(60)).orElseThrow();
            assertThat(badJob.getStatus()).isEqualTo(InferenceJobStatus.FAILED_EXHAUSTED);
            assertThat(badJob.getAttemptCount()).isEqualTo(1);
            Prediction badPred = predictionRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                    badSess.getId(), MODEL_VERSION, sStart, sStart.plusSeconds(60)).orElseThrow();
            assertThat(badPred.getPredictionStatus()).isEqualTo(PredictionStatus.ERROR);
            idx++;
        }

        // Permanent quality rejections preserve DEGRADED vs REJECTED and NEVER create retries
        MonitoringSession degSess = createSession("PT-DEG-01", "DEV-DEG-01", "SES-DEG-01", startedAt);
        handler = req -> new StubReply(200, degradedJson(), 0L);
        scheduler.tickAt(startedAt.plusSeconds(63));
        Prediction degPred = predictionRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                degSess.getId(), MODEL_VERSION, startedAt, startedAt.plusSeconds(60)).orElseThrow();
        assertThat(degPred.getPredictionStatus()).isEqualTo(PredictionStatus.REJECTED);
        assertThat(degPred.getInputQualityStatus()).isEqualTo(InputQualityStatus.DEGRADED);
        assertThat(degPred.getPredictedClass()).isNull();
        assertThat(jobRepository.findBySessionIdAndStatus(degSess.getId(), InferenceJobStatus.RETRY_WAIT)).isEmpty();
    }

    @Test
    void recoversFromMidHttpCrashWithoutSkippingCursorAndRollsBackAtomicOnPersistenceFailure() {
        Instant startedAt = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MILLIS);
        MonitoringSession session = createSession("PT-REC-01", "DEV-REC-01", "SES-REC-01", startedAt);

        // Verify atomic rollback when PredictionService fails mid-exhaustion commit
        PredictionService failingPredictionService = Mockito.mock(PredictionService.class);
        Mockito.when(failingPredictionService.recordPrediction(Mockito.any()))
                .thenThrow(new RuntimeException("simulated DB failure mid-commit"));
        InferenceJobService rollbackJobService = new InferenceJobService(
                jobRepository, failingPredictionService, sessionRepository, schedulerProperties, transactionManager);

        // Simulate crash mid-HTTP on Window 1 [startedAt+10s, startedAt+70s) before persistence commits:
        // WindowEvaluationService succeeds HTTP? Even simpler: fail InferenceJobService during Window 1 evaluation via mock!
        // First verify rollback on Window 0 exhaustion:
        for (int i = 0; i < 3; i++) {
            rollbackJobService.recordRetryableFailure(session.getId(), MODEL_VERSION,
                    startedAt, startedAt.plusSeconds(60), "err", 10, startedAt.plusSeconds(63 + i * 30L));
        }
        assertThatThrownBy(() -> rollbackJobService.recordRetryableFailure(session.getId(), MODEL_VERSION,
                startedAt, startedAt.plusSeconds(60), "err", 10, startedAt.plusSeconds(200)))
                .isInstanceOf(RuntimeException.class);

        // Verify job rolled back cleanly to attemptCount=3, RETRY_WAIT (NOT FAILED_EXHAUSTED!)
        InferenceWindowJob afterRollback = jobRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                session.getId(), MODEL_VERSION, startedAt, startedAt.plusSeconds(60)).orElseThrow();
        assertThat(afterRollback.getStatus()).isEqualTo(InferenceJobStatus.RETRY_WAIT);
        assertThat(afterRollback.getAttemptCount()).isEqualTo(3);

        // Simulate crash mid-HTTP on Window 1 [startedAt+10s, startedAt+70s) after rollback left Window 0 due at 213s:
        // Mock Window 1 to crash; Mockito default returns null for subsequent windows too -> haltedSessionsThisTick + cursor guard
        // guarantees neither Window 1 nor any later window (Window 2..6) gets evaluated or skipped!
        WindowEvaluationService crashingEval = Mockito.mock(WindowEvaluationService.class);
        Mockito.when(crashingEval.evaluateOnce(Mockito.eq(session.getId()), Mockito.eq(startedAt.plusSeconds(10)), Mockito.eq(startedAt.plusSeconds(70)), Mockito.any()))
                .thenThrow(new Error("simulated JVM crash during Window 1 HTTP"));

        SlidingWindowScheduler crashedScheduler = new SlidingWindowScheduler(
                sessionRepository, predictionRepository, jobRepository, inferenceJobService,
                crashingEval, simulationLifecycleService, aiProperties, schedulerProperties);
        try {
            // Tick at 200s (< 213s): retry for Window 0 is not due yet; forward cursor sits at Window 1 (70s), which crashes!
            crashedScheduler.tickAt(startedAt.plusSeconds(200));
        } finally {
            crashedScheduler.shutdown();
        }
        // Window 0 remains RETRY_WAIT (attempt 3, rolled back cleanly); Window 1..6 remain unpersisted (never skipped!)
        assertThat(predictionRepository.findWindow(session.getId(), Instant.EPOCH, startedAt.plusSeconds(500), PageRequest.of(0, 50)).getContent()).isEmpty();
        assertThat(jobRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                session.getId(), MODEL_VERSION, startedAt.plusSeconds(10), startedAt.plusSeconds(70))).isEmpty();
        assertThat(jobRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                session.getId(), MODEL_VERSION, startedAt.plusSeconds(20), startedAt.plusSeconds(80))).isEmpty();

        // Simulate process restart with fresh scheduler instance at 215s (past 123s + 90s = 213s backoff):
        // Window 0 retry succeeds -> SUCCEEDED + Prediction, AND forward cursor resumes cleanly at Window 1 (no skip!)
        SlidingWindowScheduler restartedScheduler = newScheduler(aiProperties);
        try {
            handler = req -> new StubReply(200, completedNormalJson(), 0L);
            restartedScheduler.tickAt(startedAt.plusSeconds(215));

            assertThat(jobRepository.findById(afterRollback.getId()).orElseThrow().getStatus())
                    .isEqualTo(InferenceJobStatus.SUCCEEDED);
            Prediction w0 = predictionRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                    session.getId(), MODEL_VERSION, startedAt, startedAt.plusSeconds(60)).orElseThrow();
            assertThat(w0.getPredictionStatus()).isEqualTo(PredictionStatus.COMPLETED);

            // Cursor also evaluated Window 1 [startedAt+10s, startedAt+70s) without skipping
            Prediction w1 = predictionRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                    session.getId(), MODEL_VERSION, startedAt.plusSeconds(10), startedAt.plusSeconds(70)).orElseThrow();
            assertThat(w1.getPredictionStatus()).isEqualTo(PredictionStatus.COMPLETED);
        } finally {
            restartedScheduler.shutdown();
        }
    }

    @Test
    void concurrentSchedulerTicksNeverDuplicateWorkForSameSessionAndWindow() throws Exception {
        Instant startedAt = Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.MILLIS);
        MonitoringSession session = createSession("PT-CON-01", "DEV-CON-01", "SES-CON-01", startedAt);
        var barrier = new java.util.concurrent.CountDownLatch(1);
        var slowStarted = new java.util.concurrent.CountDownLatch(1);
        handler = req -> {
            slowStarted.countDown();
            try {
                barrier.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new StubReply(200, completedNormalJson(), 0L);
        };

        var t1 = java.util.concurrent.CompletableFuture.supplyAsync(() -> scheduler.tickAt(startedAt.plusSeconds(63)));
        assertThat(slowStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // Concurrent second tick while tick 1 is blocked in sidecar HTTP skips overlap cleanly
        var overlapping = scheduler.tickAt(startedAt.plusSeconds(63));
        assertThat(overlapping.skippedOverlap()).isTrue();
        barrier.countDown();
        assertThat(t1.get().windowsEvaluated()).isEqualTo(1);
        assertThat(recordedCalls).hasSize(1);
        assertThat(predictionRepository.findWindow(session.getId(), Instant.EPOCH, startedAt.plusSeconds(200), PageRequest.of(0, 20)).getContent())
                .hasSize(1);
    }

    private MonitoringSession createSession(String patientCode, String deviceCode, String sessionCode, Instant startedAt) {
        UUID by = admin.getId();
        Patient p = patientRepository.save(new Patient(patientCode, "Patient " + patientCode, null, null, null,
                SourceType.SIMULATED, by));
        Device d = deviceRepository.save(new Device(deviceCode, SourceType.SIMULATED, null, null, by));
        DeviceAssignment a = assignmentRepository.save(new DeviceAssignment(d.getId(), p.getId(), by));
        return sessionRepository.save(new MonitoringSession(sessionCode, p.getId(), a.getId(), by, startedAt));
    }

    private static String completedNormalJson() {
        return """
                {
                  "modelVersion": "phase7_model_c:1.0.0",
                  "predictedClass": "NORMAL",
                  "confidenceScore": 0.910000,
                  "predictionStatus": "COMPLETED",
                  "inputQualityStatus": "VALID",
                  "processingTimeMs": 14,
                  "errorMessage": null
                }
                """;
    }

    private static String degradedJson() {
        return """
                {
                  "modelVersion": "phase7_model_c:1.0.0",
                  "predictedClass": null,
                  "confidenceScore": null,
                  "predictionStatus": "REJECTED",
                  "inputQualityStatus": "DEGRADED",
                  "processingTimeMs": 6,
                  "errorMessage": "Excessive signal dropout"
                }
                """;
    }
}
