package com.somnisense.backend.prediction;

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
import com.somnisense.backend.prediction.dto.RecordPredictionCommand;
import com.somnisense.backend.prediction.dto.RecordPredictionResult;
import com.somnisense.backend.prediction.entity.InputQualityStatus;
import com.somnisense.backend.prediction.entity.PredictedClass;
import com.somnisense.backend.prediction.entity.PredictionStatus;
import com.somnisense.backend.prediction.service.PredictionService;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.support.TestDatabase;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level persistence and validation tests for PredictionService.
 */
@SpringBootTest
@ActiveProfiles("test")
class PredictionServiceTest {

    private static final String MODEL_VERSION = "phase7_model_c:1.0.0";

    @Autowired
    private PredictionService predictionService;
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
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User admin;
    private MonitoringSession session;
    private Instant t0;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        admin = userRepository.save(new User("admin", "admin@test.local",
                passwordEncoder.encode("a-test-password-123"), Role.ADMIN));
        UUID by = admin.getId();
        Patient patient = patientRepository.save(new Patient("PT-PRED0001", "Pred Patient", null, null, null,
                SourceType.SIMULATED, by));
        Device device = deviceRepository.save(new Device("DEV-PRED1", SourceType.SIMULATED, null, null, by));
        DeviceAssignment assignment = assignmentRepository.save(new DeviceAssignment(device.getId(), patient.getId(), by));
        t0 = Instant.parse("2026-11-26T00:00:00Z");
        session = sessionRepository.save(new MonitoringSession("SES-PRED-001", patient.getId(),
                assignment.getId(), by, t0));
    }

    @Test
    void persistsValidCompletedAndNeedsReviewPredictions() {
        RecordPredictionResult normalResult = predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0, t0.plusSeconds(30),
                PredictedClass.NORMAL, new BigDecimal("0.184321"),
                PredictionStatus.COMPLETED, InputQualityStatus.VALID,
                t0.plusSeconds(31), 28, null));

        assertThat(normalResult.isCreated()).isTrue();
        assertThat(normalResult.getPrediction().getPredictedClass()).isEqualTo(PredictedClass.NORMAL);
        assertThat(normalResult.getPrediction().getConfidenceScore()).isEqualByComparingTo("0.184321");
        assertThat(normalResult.getPrediction().getPredictionStatus()).isEqualTo(PredictionStatus.COMPLETED);

        RecordPredictionResult apneaResult = predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0.plusSeconds(30), t0.plusSeconds(60),
                PredictedClass.APNEA_EVENT, new BigDecimal("0.8742104"),
                PredictionStatus.NEEDS_REVIEW, InputQualityStatus.DEGRADED,
                t0.plusSeconds(61), 45, null));

        assertThat(apneaResult.isCreated()).isTrue();
        assertThat(apneaResult.getPrediction().getPredictedClass()).isEqualTo(PredictedClass.APNEA_EVENT);
        assertThat(apneaResult.getPrediction().getConfidenceScore()).isEqualByComparingTo("0.874210");
        assertThat(apneaResult.getPrediction().getInputQualityStatus()).isEqualTo(InputQualityStatus.DEGRADED);
    }

    @Test
    void persistsRejectedAndErrorPredictionsWithoutFabricatedValues() {
        RecordPredictionResult rejected = predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0, t0.plusSeconds(30),
                null, null, PredictionStatus.REJECTED, InputQualityStatus.REJECTED,
                t0.plusSeconds(31), 10, "Insufficient valid samples in window"));

        assertThat(rejected.isCreated()).isTrue();
        assertThat(rejected.getPrediction().getPredictedClass()).isNull();
        assertThat(rejected.getPrediction().getConfidenceScore()).isNull();
        assertThat(rejected.getPrediction().getPredictionStatus()).isEqualTo(PredictionStatus.REJECTED);
        assertThat(rejected.getPrediction().getInputQualityStatus()).isEqualTo(InputQualityStatus.REJECTED);

        RecordPredictionResult error = predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0.plusSeconds(30), t0.plusSeconds(60),
                null, null, PredictionStatus.ERROR, null,
                t0.plusSeconds(61), 500, "Model sidecar timeout"));

        assertThat(error.isCreated()).isTrue();
        assertThat(error.getPrediction().getPredictedClass()).isNull();
        assertThat(error.getPrediction().getConfidenceScore()).isNull();
        assertThat(error.getPrediction().getPredictionStatus()).isEqualTo(PredictionStatus.ERROR);
        assertThat(error.getPrediction().getErrorMessage()).isEqualTo("Model sidecar timeout");

        // Rejects any attempt to attach fabricated class/confidence to REJECTED or ERROR
        assertThatThrownBy(() -> predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0.plusSeconds(60), t0.plusSeconds(90),
                PredictedClass.NORMAL, new BigDecimal("0.0"), PredictionStatus.REJECTED, InputQualityStatus.REJECTED,
                t0.plusSeconds(91), 5, "Rejected")))
                .isInstanceOf(InvalidRequestException.class);

        assertThatThrownBy(() -> predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0.plusSeconds(60), t0.plusSeconds(90),
                null, new BigDecimal("0.5"), PredictionStatus.ERROR, null,
                t0.plusSeconds(91), 5, "Failed")))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsInvalidConfidenceWindowAndParameters() {
        // Negative confidence
        assertThatThrownBy(() -> predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0, t0.plusSeconds(30),
                PredictedClass.NORMAL, new BigDecimal("-0.01"), PredictionStatus.COMPLETED, InputQualityStatus.VALID,
                t0.plusSeconds(31), 20, null)))
                .isInstanceOf(InvalidRequestException.class);

        // Confidence > 1
        assertThatThrownBy(() -> predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0, t0.plusSeconds(30),
                PredictedClass.APNEA_EVENT, new BigDecimal("1.000001"), PredictionStatus.NEEDS_REVIEW, InputQualityStatus.VALID,
                t0.plusSeconds(31), 20, null)))
                .isInstanceOf(InvalidRequestException.class);

        // Missing confidence on COMPLETED
        assertThatThrownBy(() -> predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0, t0.plusSeconds(30),
                PredictedClass.NORMAL, null, PredictionStatus.COMPLETED, InputQualityStatus.VALID,
                t0.plusSeconds(31), 20, null)))
                .isInstanceOf(InvalidRequestException.class);

        // Invalid window (start == end and start > end)
        assertThatThrownBy(() -> predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0, t0,
                PredictedClass.NORMAL, new BigDecimal("0.2"), PredictionStatus.COMPLETED, InputQualityStatus.VALID,
                t0.plusSeconds(1), 20, null)))
                .isInstanceOf(InvalidRequestException.class);

        assertThatThrownBy(() -> predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0.plusSeconds(30), t0,
                PredictedClass.NORMAL, new BigDecimal("0.2"), PredictionStatus.COMPLETED, InputQualityStatus.VALID,
                t0.plusSeconds(31), 20, null)))
                .isInstanceOf(InvalidRequestException.class);

        // Blank model version
        assertThatThrownBy(() -> predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), "   ", t0, t0.plusSeconds(30),
                PredictedClass.NORMAL, new BigDecimal("0.2"), PredictionStatus.COMPLETED, InputQualityStatus.VALID,
                t0.plusSeconds(31), 20, null)))
                .isInstanceOf(InvalidRequestException.class);

        // Negative processingTimeMs
        assertThatThrownBy(() -> predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0, t0.plusSeconds(30),
                PredictedClass.NORMAL, new BigDecimal("0.2"), PredictionStatus.COMPLETED, InputQualityStatus.VALID,
                t0.plusSeconds(31), -1, null)))
                .isInstanceOf(InvalidRequestException.class);

        // processedAt before windowStart
        assertThatThrownBy(() -> predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0, t0.plusSeconds(30),
                PredictedClass.NORMAL, new BigDecimal("0.2"), PredictionStatus.COMPLETED, InputQualityStatus.VALID,
                t0.minusSeconds(1), 10, null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsNonexistentAndCancelledSessions() {
        assertThatThrownBy(() -> predictionService.recordPrediction(new RecordPredictionCommand(
                UUID.randomUUID(), MODEL_VERSION, t0, t0.plusSeconds(30),
                PredictedClass.NORMAL, new BigDecimal("0.2"), PredictionStatus.COMPLETED, InputQualityStatus.VALID,
                t0.plusSeconds(31), 15, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        session.finish(SessionStatus.CANCELLED, admin.getId());
        sessionRepository.save(session);

        assertThatThrownBy(() -> predictionService.recordPrediction(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0, t0.plusSeconds(30),
                PredictedClass.NORMAL, new BigDecimal("0.2"), PredictionStatus.COMPLETED, InputQualityStatus.VALID,
                t0.plusSeconds(31), 15, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void duplicateWindowIsHandledIdempotentlyWithoutCreatingSecondRow() {
        RecordPredictionCommand cmd = new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, t0, t0.plusSeconds(30),
                PredictedClass.APNEA_EVENT, new BigDecimal("0.810000"),
                PredictionStatus.NEEDS_REVIEW, InputQualityStatus.VALID,
                t0.plusSeconds(31), 32, null);

        RecordPredictionResult first = predictionService.recordPrediction(cmd);
        RecordPredictionResult duplicate = predictionService.recordPrediction(cmd);

        assertThat(first.isCreated()).isTrue();
        assertThat(duplicate.isCreated()).isFalse();
        assertThat(duplicate.getPrediction().getId()).isEqualTo(first.getPrediction().getId());
        assertThat(jdbcTemplate.queryForObject("select count(*) from predictions", Integer.class)).isEqualTo(1);
    }
}
