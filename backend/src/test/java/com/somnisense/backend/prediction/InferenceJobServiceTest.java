package com.somnisense.backend.prediction;

import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
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
import com.somnisense.backend.session.entity.MonitoringSession;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class InferenceJobServiceTest {

    private static final String MODEL_VERSION = "phase7_model_c:1.0.0";

    @Autowired
    private InferenceJobService jobService;
    @Autowired
    private InferenceWindowJobRepository jobRepository;
    @Autowired
    private PredictionRepository predictionRepository;
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

    private MonitoringSession session;
    private Instant winStart;
    private Instant winEnd;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        User admin = userRepository.save(new User("adminjob", "adminjob@test.local",
                passwordEncoder.encode("a-test-password-123"), Role.ADMIN));
        UUID by = admin.getId();
        Patient patient = patientRepository.save(new Patient("PT-JOB01", "Job Patient", null, null, null,
                SourceType.SIMULATED, by));
        Device device = deviceRepository.save(new Device("DEV-JOB01", SourceType.SIMULATED, null, null, by));
        DeviceAssignment assignment = assignmentRepository.save(new DeviceAssignment(device.getId(), patient.getId(), by));
        Instant startedAt = Instant.now().minusSeconds(400).truncatedTo(ChronoUnit.MILLIS);
        session = sessionRepository.save(new MonitoringSession("SES-JOB-001", patient.getId(), assignment.getId(), by, startedAt));
        winStart = startedAt;
        winEnd = startedAt.plusSeconds(60);
    }

    @Test
    void retriesWithBackoffUntilAttemptFourThenWritesSingleTerminalError() {
        // Attempts 1, 2, 3 remain RETRY_WAIT with no Prediction row
        InferenceWindowJob a1 = jobService.recordRetryableFailure(
                session.getId(), MODEL_VERSION, winStart, winEnd, "HTTP 503", 12);
        assertThat(a1.getStatus()).isEqualTo(InferenceJobStatus.RETRY_WAIT);
        assertThat(a1.getAttemptCount()).isEqualTo(1);
        assertThat(a1.getNextRetryAt()).isAfterOrEqualTo(a1.getUpdatedAt().plusSeconds(10));
        assertThat(predictionRepository.findAll()).isEmpty();

        InferenceWindowJob a2 = jobService.recordRetryableFailure(
                session.getId(), MODEL_VERSION, winStart, winEnd, "timeout", 5000);
        assertThat(a2.getStatus()).isEqualTo(InferenceJobStatus.RETRY_WAIT);
        assertThat(a2.getAttemptCount()).isEqualTo(2);
        assertThat(a2.getNextRetryAt()).isAfterOrEqualTo(a2.getUpdatedAt().plusSeconds(30));

        InferenceWindowJob a3 = jobService.recordRetryableFailure(
                session.getId(), MODEL_VERSION, winStart, winEnd, "HTTP 502", 45);
        assertThat(a3.getStatus()).isEqualTo(InferenceJobStatus.RETRY_WAIT);
        assertThat(a3.getAttemptCount()).isEqualTo(3);
        assertThat(a3.getNextRetryAt()).isAfterOrEqualTo(a3.getUpdatedAt().plusSeconds(90));
        assertThat(predictionRepository.findAll()).isEmpty();

        // Attempt 4 exhausts retries -> FAILED_EXHAUSTED + single ERROR row
        InferenceWindowJob a4 = jobService.recordRetryableFailure(
                session.getId(), MODEL_VERSION, winStart, winEnd, "still 503", 99);
        assertThat(a4.getStatus()).isEqualTo(InferenceJobStatus.FAILED_EXHAUSTED);
        assertThat(a4.getAttemptCount()).isEqualTo(4);
        assertThat(a4.getNextRetryAt()).isNull();

        List<Prediction> predictions = predictionRepository.findAll();
        assertThat(predictions).hasSize(1);
        assertThat(predictions.get(0).getPredictionStatus()).isEqualTo(PredictionStatus.ERROR);
        assertThat(predictions.get(0).getPredictedClass()).isNull();
        assertThat(predictions.get(0).getConfidenceScore()).isNull();
    }

    @Test
    void retrySuccessMarksJobSucceededAndWritesSinglePrediction() {
        jobService.recordRetryableFailure(session.getId(), MODEL_VERSION, winStart, winEnd, "HTTP 503", 10);
        var result = jobService.recordSuccess(new RecordPredictionCommand(
                session.getId(), MODEL_VERSION, winStart, winEnd,
                PredictedClass.NORMAL, new BigDecimal("0.88"),
                PredictionStatus.COMPLETED, InputQualityStatus.VALID,
                Instant.now(), 25, null
        ));
        assertThat(result.isCreated()).isTrue();
        InferenceWindowJob done = jobRepository
                .findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(session.getId(), MODEL_VERSION, winStart, winEnd)
                .orElseThrow();
        assertThat(done.getStatus()).isEqualTo(InferenceJobStatus.SUCCEEDED);
        assertThat(done.getAttemptCount()).isEqualTo(2);
    }

    @Test
    void nonRetryableFailureImmediatelyWritesTerminalErrorWithoutRetries() {
        InferenceWindowJob exhausted = jobService.recordNonRetryableFailure(
                session.getId(), MODEL_VERSION, winStart, winEnd, "HTTP 401 unauthorized", 5);
        assertThat(exhausted.getStatus()).isEqualTo(InferenceJobStatus.FAILED_EXHAUSTED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(1);

        List<Prediction> predictions = predictionRepository.findAll();
        assertThat(predictions).hasSize(1);
        assertThat(predictions.get(0).getPredictionStatus()).isEqualTo(PredictionStatus.ERROR);
    }
}
