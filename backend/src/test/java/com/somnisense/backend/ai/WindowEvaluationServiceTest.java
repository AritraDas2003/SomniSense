package com.somnisense.backend.ai;

import com.jayway.jsonpath.JsonPath;
import com.somnisense.backend.ai.client.AiSidecarClient;
import com.somnisense.backend.ai.config.AiProperties;
import com.somnisense.backend.ai.service.WindowEvaluationService;
import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.common.exception.ConflictException;
import com.somnisense.backend.common.exception.InvalidRequestException;
import com.somnisense.backend.common.exception.ResourceNotFoundException;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.prediction.dto.RecordPredictionResult;
import com.somnisense.backend.prediction.entity.InputQualityStatus;
import com.somnisense.backend.prediction.entity.PredictedClass;
import com.somnisense.backend.prediction.entity.PredictionStatus;
import com.somnisense.backend.prediction.service.PredictionService;
import com.somnisense.backend.reading.entity.SensorReading;
import com.somnisense.backend.reading.repository.SensorReadingRepository;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.support.TestDatabase;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for WindowEvaluationService and AiSidecarClient against somnisense_test_db
 * using a local ephemeral HTTP stub server.
 */
@SpringBootTest
@ActiveProfiles("test")
class WindowEvaluationServiceTest {

    private static final String AI_KEY = "test-internal-ai-key-0123456789abcdef-0123456789";

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
    private PredictionService predictionService;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpServer server;
    private WindowEvaluationService evaluationService;
    private User admin;
    private Device device;
    private MonitoringSession session;
    private Instant ctxStart;
    private Instant winStart;
    private Instant winEnd;

    private final AtomicInteger serverCalls = new AtomicInteger();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastKeyHeader = new AtomicReference<>();
    private final AtomicReference<String> lastCorrelationHeader = new AtomicReference<>();
    private final AtomicReference<Integer> stubStatus = new AtomicReference<>(200);
    private final AtomicReference<String> stubResponseBody = new AtomicReference<>();
    private final AtomicReference<Long> stubDelayMs = new AtomicReference<>(0L);

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(jdbcTemplate);
        serverCalls.set(0);
        stubStatus.set(200);
        stubDelayMs.set(0L);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/ai/evaluate-window", exchange -> {
            serverCalls.incrementAndGet();
            lastKeyHeader.set(exchange.getRequestHeaders().getFirst("X-Internal-AI-Key"));
            lastCorrelationHeader.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            long delay = stubDelayMs.get();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = (stubResponseBody.get() == null ? "{}" : stubResponseBody.get()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(stubStatus.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AiProperties props = new AiProperties(baseUrl, AI_KEY, Duration.ofSeconds(1), Duration.ofMillis(400),
                "phase7_model_c:1.0.0");
        AiSidecarClient client = new AiSidecarClient(props, jsonMapper);
        evaluationService = new WindowEvaluationService(sessionRepository, readingRepository, client, predictionService, props);

        admin = userRepository.save(new User("admin", "admin@test.local",
                passwordEncoder.encode("a-test-password-123"), Role.ADMIN));
        UUID by = admin.getId();
        Patient patient = patientRepository.save(new Patient("PT-AI00001", "AI Patient", null, null, null,
                SourceType.SIMULATED, by));
        device = deviceRepository.save(new Device("DEV-AI1", SourceType.SIMULATED, null, null, by));
        DeviceAssignment assignment = assignmentRepository.save(new DeviceAssignment(device.getId(), patient.getId(), by));

        ctxStart = Instant.now().minusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        winStart = ctxStart.plusSeconds(300);
        winEnd = ctxStart.plusSeconds(360);
        session = sessionRepository.save(new MonitoringSession("SES-AI-001", patient.getId(),
                assignment.getId(), by, ctxStart));
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOrderedCausalContextAndPersistsValidNeedsReviewIdempotently() {
        // Insert out-of-order and same-measuredAt tie-broken readings
        Instant t1 = ctxStart.plusSeconds(305);
        readingRepository.save(new SensorReading(session.getId(), device.getId(), "k-late-recv",
                new BigDecimal("85.00"), new BigDecimal("74.00"), null, t1, t1.plusSeconds(2)));
        readingRepository.save(new SensorReading(session.getId(), device.getId(), "k-early-recv",
                new BigDecimal("86.00"), new BigDecimal("73.00"), null, t1, t1.plusSeconds(1)));
        readingRepository.save(new SensorReading(session.getId(), device.getId(), "k-ctx",
                new BigDecimal("96.00"), new BigDecimal("64.00"), null, ctxStart.plusSeconds(10), ctxStart.plusSeconds(11)));
        // Outside [contextStart, windowEnd) -> must not be included
        readingRepository.save(new SensorReading(session.getId(), device.getId(), "k-outside",
                new BigDecimal("99.00"), new BigDecimal("60.00"), null, winEnd, winEnd.plusSeconds(1)));

        stubResponseBody.set("""
                {
                  "sessionId": "%s",
                  "modelVersion": "phase7_model_c:1.0.0",
                  "windowStart": "%s",
                  "windowEnd": "%s",
                  "predictionStatus": "NEEDS_REVIEW",
                  "predictedClass": "APNEA_EVENT",
                  "confidenceScore": 0.881234,
                  "inputQualityStatus": "VALID",
                  "decisionThreshold": 0.50,
                  "processingTimeMs": 7,
                  "errorMessage": null
                }
                """.formatted(session.getId(), winStart, winEnd));

        MDC.put("correlationId", "cid-stage3-test");
        RecordPredictionResult first = evaluationService.evaluateAndPersist(session.getId(), winStart, winEnd);
        assertThat(first.isCreated()).isTrue();
        assertThat(first.getPrediction().getPredictionStatus()).isEqualTo(PredictionStatus.NEEDS_REVIEW);
        assertThat(first.getPrediction().getPredictedClass()).isEqualTo(PredictedClass.APNEA_EVENT);
        assertThat(first.getPrediction().getConfidenceScore()).isEqualByComparingTo("0.881234");
        assertThat(first.getPrediction().getInputQualityStatus()).isEqualTo(InputQualityStatus.VALID);

        assertThat(lastKeyHeader.get()).isEqualTo(AI_KEY);
        assertThat(lastCorrelationHeader.get()).isEqualTo("cid-stage3-test");

        String req = lastRequestBody.get();
        assertThat((String) JsonPath.read(req, "$.contextStart")).isEqualTo(ctxStart.toString());
        List<String> measured = JsonPath.read(req, "$.readings[*].measuredAt");
        List<String> received = JsonPath.read(req, "$.readings[*].receivedAt");
        assertThat(measured).containsExactly(ctxStart.plusSeconds(10).toString(), t1.toString(), t1.toString());
        assertThat(received).containsExactly(
                ctxStart.plusSeconds(11).toString(),
                t1.plusSeconds(1).toString(),
                t1.plusSeconds(2).toString());

        // Idempotent repeat for same window
        RecordPredictionResult second = evaluationService.evaluateAndPersist(session.getId(), winStart, winEnd);
        assertThat(second.isCreated()).isFalse();
        assertThat(second.getPrediction().getId()).isEqualTo(first.getPrediction().getId());
    }

    @Test
    void persistsDegradedAndRejectedWithoutFabricatedValues() {
        stubResponseBody.set("""
                {
                  "sessionId": "%s",
                  "modelVersion": "phase7_model_c:1.0.0",
                  "windowStart": "%s",
                  "windowEnd": "%s",
                  "predictionStatus": "REJECTED",
                  "predictedClass": null,
                  "confidenceScore": null,
                  "inputQualityStatus": "DEGRADED",
                  "decisionThreshold": 0.50,
                  "processingTimeMs": 2,
                  "errorMessage": "SPO2:SENSITIVITY_ONLY_GAP_8S"
                }
                """.formatted(session.getId(), winStart, winEnd));

        RecordPredictionResult degraded = evaluationService.evaluateAndPersist(session.getId(), winStart, winEnd);
        assertThat(degraded.isCreated()).isTrue();
        assertThat(degraded.getPrediction().getPredictionStatus()).isEqualTo(PredictionStatus.REJECTED);
        assertThat(degraded.getPrediction().getInputQualityStatus()).isEqualTo(InputQualityStatus.DEGRADED);
        assertThat(degraded.getPrediction().getPredictedClass()).isNull();
        assertThat(degraded.getPrediction().getConfidenceScore()).isNull();
        assertThat(degraded.getPrediction().getErrorMessage()).isEqualTo("SPO2:SENSITIVITY_ONLY_GAP_8S");
    }

    @Test
    void persistsErrorSafelyOnSidecarFailureOrTimeout() {
        stubStatus.set(500);
        stubResponseBody.set("{\"code\":\"INTERNAL_ERROR\"}");
        RecordPredictionResult err500 = evaluationService.evaluateAndPersist(session.getId(), winStart, winEnd);
        assertThat(err500.isCreated()).isTrue();
        assertThat(err500.getPrediction().getPredictionStatus()).isEqualTo(PredictionStatus.ERROR);
        assertThat(err500.getPrediction().getPredictedClass()).isNull();
        assertThat(err500.getPrediction().getConfidenceScore()).isNull();
        assertThat(err500.getPrediction().getErrorMessage()).contains("HTTP 500");

        // Timeout on a subsequent window
        Instant win2Start = winEnd;
        Instant win2End = winEnd.plusSeconds(60);
        stubStatus.set(200);
        stubDelayMs.set(700L);
        RecordPredictionResult timeoutRes = evaluationService.evaluateAndPersist(session.getId(), win2Start, win2End);
        assertThat(timeoutRes.getPrediction().getPredictionStatus()).isEqualTo(PredictionStatus.ERROR);
        assertThat(timeoutRes.getPrediction().getPredictedClass()).isNull();
        assertThat(timeoutRes.getPrediction().getConfidenceScore()).isNull();
    }

    @Test
    void rejectsInvalidWindowAndCancelledOrUnknownSessionBeforeCallingSidecar() {
        assertThatThrownBy(() -> evaluationService.evaluateAndPersist(session.getId(), winStart, winStart.plusSeconds(30)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> evaluationService.evaluateAndPersist(UUID.randomUUID(), winStart, winEnd))
                .isInstanceOf(ResourceNotFoundException.class);

        session.finish(SessionStatus.CANCELLED, admin.getId());
        sessionRepository.save(session);
        assertThatThrownBy(() -> evaluationService.evaluateAndPersist(session.getId(), winStart, winEnd))
                .isInstanceOf(ConflictException.class);

        assertThat(serverCalls.get()).isZero();
    }

    @Test
    void rejectsFutureDatedWindowBeforePersisting() {
        Instant futureStart = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant futureEnd = futureStart.plusSeconds(60);
        stubResponseBody.set("""
                {
                  "sessionId": "%s",
                  "modelVersion": "phase7_model_c:1.0.0",
                  "windowStart": "%s",
                  "windowEnd": "%s",
                  "predictionStatus": "COMPLETED",
                  "predictedClass": "NORMAL",
                  "confidenceScore": 0.120000,
                  "inputQualityStatus": "VALID",
                  "decisionThreshold": 0.50,
                  "processingTimeMs": 3,
                  "errorMessage": null
                }
                """.formatted(session.getId(), futureStart, futureEnd));

        assertThatThrownBy(() -> evaluationService.evaluateAndPersist(session.getId(), futureStart, futureEnd))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("processedAt cannot be before windowStart");
        assertThat(jdbcTemplate.queryForObject("select count(*) from predictions", Integer.class)).isZero();
    }
}
