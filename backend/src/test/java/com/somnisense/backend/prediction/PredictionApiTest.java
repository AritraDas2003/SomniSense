package com.somnisense.backend.prediction;

import com.jayway.jsonpath.JsonPath;
import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.prediction.dto.RecordPredictionCommand;
import com.somnisense.backend.prediction.entity.InputQualityStatus;
import com.somnisense.backend.prediction.entity.PredictedClass;
import com.somnisense.backend.prediction.entity.PredictionStatus;
import com.somnisense.backend.prediction.service.PredictionService;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.support.LoggedInClient;
import com.somnisense.backend.support.TestDatabase;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP tests for Stage 2 prediction history and latest endpoints against somnisense_test_db.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PredictionApiTest {

    private static final String PW = "a-test-password-123";
    private static final String MODEL_VERSION = "phase7_model_c:1.0.0";

    @Autowired
    private MockMvc mockMvc;
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
    private PredictionService predictionService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LoggedInClient admin;
    private LoggedInClient operator;
    private MonitoringSession sessionA;
    private MonitoringSession sessionB;
    private Instant t0;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(jdbcTemplate);
        User adminUser = userRepository.save(new User("admin", "admin@test.local", passwordEncoder.encode(PW), Role.ADMIN));
        userRepository.save(new User("operator1", "op1@test.local", passwordEncoder.encode(PW), Role.OPERATOR));
        admin = LoggedInClient.login(mockMvc, "admin", PW);
        operator = LoggedInClient.login(mockMvc, "operator1", PW);

        UUID by = adminUser.getId();
        Patient patientA = patientRepository.save(new Patient("PT-PAPI0001", "Patient A", null, null, null,
                SourceType.SIMULATED, by));
        Patient patientB = patientRepository.save(new Patient("PT-PAPI0002", "Patient B", null, null, null,
                SourceType.SIMULATED, by));
        Device deviceA = deviceRepository.save(new Device("DEV-PAPI1", SourceType.SIMULATED, null, null, by));
        Device deviceB = deviceRepository.save(new Device("DEV-PAPI2", SourceType.SIMULATED, null, null, by));
        DeviceAssignment assignA = assignmentRepository.save(new DeviceAssignment(deviceA.getId(), patientA.getId(), by));
        DeviceAssignment assignB = assignmentRepository.save(new DeviceAssignment(deviceB.getId(), patientB.getId(), by));

        t0 = Instant.parse("2026-11-26T00:00:00Z");
        sessionA = sessionRepository.save(new MonitoringSession("SES-PAPI-001", patientA.getId(), assignA.getId(), by, t0));
        sessionB = sessionRepository.save(new MonitoringSession("SES-PAPI-002", patientB.getId(), assignB.getId(), by, t0));
    }

    private String baseUrl(UUID sessionId) {
        return "/api/v1/sessions/" + sessionId + "/predictions";
    }

    private void record(UUID sessionId, Instant start, Instant end, PredictedClass cls, String conf,
                        PredictionStatus status, InputQualityStatus quality) {
        predictionService.recordPrediction(new RecordPredictionCommand(
                sessionId, MODEL_VERSION, start, end, cls,
                conf == null ? null : new BigDecimal(conf),
                status, quality, end.plusSeconds(1), 25,
                status == PredictionStatus.REJECTED ? "Window rejected" : null));
    }

    @Test
    void historyIsPaginatedNewestFirstFilteredByDateAndScopedToSession() throws Exception {
        // 3 predictions in sessionA, plus 1 in sessionB (must never leak into sessionA results)
        record(sessionA.getId(), t0, t0.plusSeconds(30), PredictedClass.NORMAL, "0.120000",
                PredictionStatus.COMPLETED, InputQualityStatus.VALID);
        record(sessionA.getId(), t0.plusSeconds(30), t0.plusSeconds(60), PredictedClass.APNEA_EVENT, "0.850000",
                PredictionStatus.NEEDS_REVIEW, InputQualityStatus.VALID);
        record(sessionA.getId(), t0.plusSeconds(60), t0.plusSeconds(90), null, null,
                PredictionStatus.REJECTED, InputQualityStatus.REJECTED);
        record(sessionB.getId(), t0.plusSeconds(90), t0.plusSeconds(120), PredictedClass.APNEA_EVENT, "0.950000",
                PredictionStatus.NEEDS_REVIEW, InputQualityStatus.VALID);

        // Page 0, size 2 -> newest two windows in sessionA (t0+90s, t0+60s)
        String page0 = mockMvc.perform(operator.with(get(baseUrl(sessionA.getId()))).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn().getResponse().getContentAsString();
        List<String> windowEnds = JsonPath.read(page0, "$.content[*].windowEnd");
        assertThat(windowEnds).containsExactly(
                t0.plusSeconds(90).toString(),
                t0.plusSeconds(60).toString());

        // Page 1, size 2 -> oldest window in sessionA (t0+30s)
        mockMvc.perform(operator.with(get(baseUrl(sessionA.getId()))).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].windowEnd").value(t0.plusSeconds(30).toString()))
                .andExpect(jsonPath("$.content[0].predictedClass").value("NORMAL"));

        // Date filter [t0+31s, t0+90s) matches only window ending at t0+60s
        mockMvc.perform(operator.with(get(baseUrl(sessionA.getId())))
                        .param("from", t0.plusSeconds(31).toString())
                        .param("to", t0.plusSeconds(90).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].predictedClass").value("APNEA_EVENT"))
                .andExpect(jsonPath("$.content[0].predictionStatus").value("NEEDS_REVIEW"));

        // Admin role also permitted
        mockMvc.perform(admin.with(get(baseUrl(sessionB.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sessionId").value(sessionB.getId().toString()));
    }

    @Test
    void latestReturnsNewestPredictionOrExplicitNoPredictionResponse() throws Exception {
        // No predictions yet -> 200 with hasPrediction=false and latest=null (never fabricated NORMAL)
        mockMvc.perform(operator.with(get(baseUrl(sessionA.getId()) + "/latest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionA.getId().toString()))
                .andExpect(jsonPath("$.sessionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.hasPrediction").value(false))
                .andExpect(jsonPath("$.latest").value(nullValue()));

        record(sessionA.getId(), t0, t0.plusSeconds(30), PredictedClass.NORMAL, "0.100000",
                PredictionStatus.COMPLETED, InputQualityStatus.VALID);
        record(sessionA.getId(), t0.plusSeconds(30), t0.plusSeconds(60), PredictedClass.APNEA_EVENT, "0.912345",
                PredictionStatus.NEEDS_REVIEW, InputQualityStatus.VALID);

        mockMvc.perform(admin.with(get(baseUrl(sessionA.getId()) + "/latest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPrediction").value(true))
                .andExpect(jsonPath("$.latest.windowEnd").value(t0.plusSeconds(60).toString()))
                .andExpect(jsonPath("$.latest.predictedClass").value("APNEA_EVENT"))
                .andExpect(jsonPath("$.latest.confidenceScore").value(0.912345))
                .andExpect(jsonPath("$.latest.predictionStatus").value("NEEDS_REVIEW"));
    }

    @Test
    void enforcesValidationNotFoundAndRbacSecurity() throws Exception {
        // Unauthenticated -> 401
        mockMvc.perform(get(baseUrl(sessionA.getId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(baseUrl(sessionA.getId()) + "/latest"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // Unauthorized role -> 403
        mockMvc.perform(get(baseUrl(sessionA.getId())).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get(baseUrl(sessionA.getId()) + "/latest").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // Unknown sessionId -> 404
        mockMvc.perform(operator.with(get(baseUrl(UUID.randomUUID()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(operator.with(get(baseUrl(UUID.randomUUID()) + "/latest")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // Malformed sessionId -> 400
        mockMvc.perform(operator.with(get("/api/v1/sessions/not-a-uuid/predictions")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Invalid query parameters -> 400
        mockMvc.perform(operator.with(get(baseUrl(sessionA.getId()))).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(operator.with(get(baseUrl(sessionA.getId()))).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(operator.with(get(baseUrl(sessionA.getId()))).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(operator.with(get(baseUrl(sessionA.getId())))
                        .param("from", t0.toString())
                        .param("to", t0.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(operator.with(get(baseUrl(sessionA.getId()))).param("from", "not-an-instant"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
