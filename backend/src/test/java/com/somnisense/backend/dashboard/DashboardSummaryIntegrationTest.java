package com.somnisense.backend.dashboard;

import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.dashboard.service.DashboardService;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.entity.PatientStatus;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.prediction.entity.InferenceJobStatus;
import com.somnisense.backend.prediction.entity.InferenceWindowJob;
import com.somnisense.backend.prediction.entity.InputQualityStatus;
import com.somnisense.backend.prediction.entity.PredictedClass;
import com.somnisense.backend.prediction.entity.Prediction;
import com.somnisense.backend.prediction.entity.PredictionStatus;
import com.somnisense.backend.prediction.repository.InferenceWindowJobRepository;
import com.somnisense.backend.prediction.repository.PredictionRepository;
import com.somnisense.backend.reading.entity.SensorReading;
import com.somnisense.backend.reading.repository.SensorReadingRepository;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.simulation.entity.SimulationRun;
import com.somnisense.backend.simulation.entity.SimulationStatus;
import com.somnisense.backend.simulation.repository.SimulationRunRepository;
import com.somnisense.backend.support.LoggedInClient;
import com.somnisense.backend.support.TestDatabase;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for GET /api/v1/dashboard/summary and PatientSummaryResponse.reviewStatus
 * against somnisense_test_db.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardSummaryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DashboardSummaryIntegrationTest.class);
    private static final String PW = "a-test-password-123";
    private static final String MODEL_VERSION = "phase7_model_c:1.0.0";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
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
    private SensorReadingRepository readingRepository;
    @Autowired
    private PredictionRepository predictionRepository;
    @Autowired
    private InferenceWindowJobRepository jobRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User adminUser;
    private LoggedInClient admin;
    private LoggedInClient operator;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(jdbcTemplate);
        adminUser = userRepository.save(new User("dash-admin", "dash-admin@test.local",
                passwordEncoder.encode(PW), Role.ADMIN));
        userRepository.save(new User("dash-op", "dash-op@test.local",
                passwordEncoder.encode(PW), Role.OPERATOR));
        admin = LoggedInClient.login(mockMvc, "dash-admin", PW);
        operator = LoggedInClient.login(mockMvc, "dash-op", PW);
    }

    @Test
    void enforcesRbacAndReturnsZeroedEmptySnapshot() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/dashboard/summary").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(admin.with(get("/api/v1/dashboard/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelVersion").value(MODEL_VERSION))
                .andExpect(jsonPath("$.summary.patientCount").value(0))
                .andExpect(jsonPath("$.summary.activeSessions").value(0))
                .andExpect(jsonPath("$.summary.activeSessionsReturned").value(0))
                .andExpect(jsonPath("$.summary.activeSessionsTruncated").value(false))
                .andExpect(jsonPath("$.summary.itemsNeedingReview").value(0))
                .andExpect(jsonPath("$.summary.dataIssues").value(0))
                .andExpect(jsonPath("$.summary.activeSessionStatuses.completed").value(0))
                .andExpect(jsonPath("$.summary.activeSessionStatuses.needsReview").value(0))
                .andExpect(jsonPath("$.summary.activeSessionStatuses.rejected").value(0))
                .andExpect(jsonPath("$.summary.activeSessionStatuses.error").value(0))
                .andExpect(jsonPath("$.summary.activeSessionStatuses.degradedWindows").value(0))
                .andExpect(jsonPath("$.summary.activeSessionStatuses.pendingRetryJobs").value(0))
                .andExpect(jsonPath("$.activeSessionsList.length()").value(0))
                .andExpect(jsonPath("$.recentPatients.length()").value(0));

        mockMvc.perform(operator.with(get("/api/v1/dashboard/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.patientCount").value(0));
    }

    @Test
    void aggregatesActiveSessionsDistinguishesLatestReviewFlagAndMasksEndedOrInactiveEntities() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        // Patient 1: ACTIVE SIMULATED session, RUNNING sim, CONNECTED device, latest prediction = NEEDS_REVIEW
        Patient p1 = createPatient("PT-DSH001", "Alice Full", "Alice Sim", SourceType.SIMULATED, PatientStatus.ACTIVE, now.minusSeconds(400));
        Device d1 = createDevice("DEV-DSH01", SourceType.SIMULATED, true, now.minusSeconds(5));
        DeviceAssignment a1 = assignmentRepository.save(new DeviceAssignment(d1.getId(), p1.getId(), adminUser.getId()));
        MonitoringSession s1 = sessionRepository.save(new MonitoringSession("SES-DSH-01", p1.getId(), a1.getId(), adminUser.getId(), now.minusSeconds(180)));
        simulationRunRepository.save(new SimulationRun(s1.getId(), "severe_osa", 42L,
                new BigDecimal("1.00"), new BigDecimal("1.00"), "clean", SimulationStatus.RUNNING,
                180L, 1L, now.plusSeconds(30), "sim-worker-1", now.minusSeconds(180)));
        readingRepository.save(new SensorReading(s1.getId(), d1.getId(), "k-1",
                new BigDecimal("88.50"), new BigDecimal("79.00"), new BigDecimal("96.00"), now.minusSeconds(5), now.minusSeconds(5)));
        predictionRepository.save(new Prediction(s1.getId(), MODEL_VERSION, now.minusSeconds(120), now.minusSeconds(60),
                PredictedClass.NORMAL, new BigDecimal("0.910000"), PredictionStatus.COMPLETED, InputQualityStatus.VALID, now.minusSeconds(58), 11, null));
        predictionRepository.save(new Prediction(s1.getId(), MODEL_VERSION, now.minusSeconds(110), now.minusSeconds(50),
                PredictedClass.APNEA_EVENT, new BigDecimal("0.880000"), PredictionStatus.NEEDS_REVIEW, InputQualityStatus.VALID, now.minusSeconds(48), 13, null));

        // Patient 2: ACTIVE PHYSICAL session, STALE device, earlier NEEDS_REVIEW + DEGRADED/COMPLETED latest + ERROR + REJECTED + RETRY_WAIT
        // Latest prediction is COMPLETED (validates D1 Option A: itemsNeedingReview should NOT count p2)
        Patient p2 = createPatient("PT-DSH002", "Bob Physical", null, SourceType.PHYSICAL, PatientStatus.ACTIVE, now.minusSeconds(300));
        Device d2 = createDevice("DEV-DSH02", SourceType.PHYSICAL, true, now.minusSeconds(60));
        DeviceAssignment a2 = assignmentRepository.save(new DeviceAssignment(d2.getId(), p2.getId(), adminUser.getId()));
        MonitoringSession s2 = sessionRepository.save(new MonitoringSession("SES-DSH-02", p2.getId(), a2.getId(), adminUser.getId(), now.minusSeconds(240)));
        readingRepository.save(new SensorReading(s2.getId(), d2.getId(), "k-2",
                new BigDecimal("94.00"), new BigDecimal("72.00"), new BigDecimal("70.00"), now.minusSeconds(60), now.minusSeconds(60)));
        predictionRepository.save(new Prediction(s2.getId(), MODEL_VERSION, now.minusSeconds(200), now.minusSeconds(140),
                PredictedClass.APNEA_EVENT, new BigDecimal("0.790000"), PredictionStatus.NEEDS_REVIEW, InputQualityStatus.VALID, now.minusSeconds(138), 12, null));
        predictionRepository.save(new Prediction(s2.getId(), MODEL_VERSION, now.minusSeconds(190), now.minusSeconds(130),
                null, null, PredictionStatus.REJECTED, InputQualityStatus.REJECTED, now.minusSeconds(128), 3, "Insufficient coverage"));
        predictionRepository.save(new Prediction(s2.getId(), MODEL_VERSION, now.minusSeconds(180), now.minusSeconds(120),
                null, null, PredictionStatus.ERROR, null, now.minusSeconds(118), 20, "Sidecar timeout"));
        predictionRepository.save(new Prediction(s2.getId(), MODEL_VERSION, now.minusSeconds(170), now.minusSeconds(110),
                PredictedClass.NORMAL, new BigDecimal("0.930000"), PredictionStatus.COMPLETED, InputQualityStatus.DEGRADED, now.minusSeconds(108), 14, null));
        jobRepository.save(new InferenceWindowJob(s2.getId(), MODEL_VERSION, now.minusSeconds(160), now.minusSeconds(100),
                InferenceJobStatus.RETRY_WAIT, 1, now.plusSeconds(10), "503", now.minusSeconds(99)));

        // Patient 3: ENDED session + DISCONNECTED active device + extra INACTIVE device (must not inflate dataIssues)
        Patient p3 = createPatient("PT-DSH003", "Carol Ended", null, SourceType.SIMULATED, PatientStatus.ACTIVE, now.minusSeconds(200));
        Device d3 = createDevice("DEV-DSH03", SourceType.SIMULATED, true, now.minusSeconds(250));
        createDevice("DEV-DSH03B", SourceType.SIMULATED, false, now.minusSeconds(500));
        DeviceAssignment a3 = assignmentRepository.save(new DeviceAssignment(d3.getId(), p3.getId(), adminUser.getId()));
        MonitoringSession s3 = new MonitoringSession("SES-DSH-03", p3.getId(), a3.getId(), adminUser.getId(), now.minusSeconds(500));
        s3.finish(SessionStatus.ENDED, adminUser.getId());
        sessionRepository.save(s3);
        predictionRepository.save(new Prediction(s3.getId(), MODEL_VERSION, now.minusSeconds(460), now.minusSeconds(400),
                PredictedClass.APNEA_EVENT, new BigDecimal("0.950000"), PredictionStatus.NEEDS_REVIEW, InputQualityStatus.VALID, now.minusSeconds(399), 10, null));

        // Patient 4: INACTIVE patient + UNKNOWN active device (lastSeenAt null) + CANCELLED session (D2: counts in patientCount)
        Patient p4 = createPatient("PT-DSH004", "Dan Inactive", null, SourceType.PHYSICAL, PatientStatus.INACTIVE, now.minusSeconds(100));
        Device d4 = createDevice("DEV-DSH04", SourceType.PHYSICAL, true, null);
        DeviceAssignment a4 = assignmentRepository.save(new DeviceAssignment(d4.getId(), p4.getId(), adminUser.getId()));
        MonitoringSession s4 = new MonitoringSession("SES-DSH-04", p4.getId(), a4.getId(), adminUser.getId(), now.minusSeconds(450));
        s4.finish(SessionStatus.CANCELLED, adminUser.getId());
        sessionRepository.save(s4);

        mockMvc.perform(operator.with(get("/api/v1/dashboard/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.patientCount").value(4))
                .andExpect(jsonPath("$.summary.activeSessions").value(2))
                .andExpect(jsonPath("$.summary.activeSessionsReturned").value(2))
                .andExpect(jsonPath("$.summary.activeSessionsTruncated").value(false))
                .andExpect(jsonPath("$.summary.itemsNeedingReview").value(1))
                .andExpect(jsonPath("$.summary.dataIssues").value(2))
                .andExpect(jsonPath("$.summary.activeSessionStatuses.completed").value(2))
                .andExpect(jsonPath("$.summary.activeSessionStatuses.needsReview").value(2))
                .andExpect(jsonPath("$.summary.activeSessionStatuses.rejected").value(1))
                .andExpect(jsonPath("$.summary.activeSessionStatuses.error").value(1))
                .andExpect(jsonPath("$.summary.activeSessionStatuses.degradedWindows").value(1))
                .andExpect(jsonPath("$.summary.activeSessionStatuses.pendingRetryJobs").value(1))
                .andExpect(jsonPath("$.activeSessionsList.length()").value(2))
                // Ordered newest startedAt desc: s1 (-180s) then s2 (-240s)
                .andExpect(jsonPath("$.activeSessionsList[0].sessionCode").value("SES-DSH-01"))
                .andExpect(jsonPath("$.activeSessionsList[0].patientDisplayName").value("Alice Sim"))
                .andExpect(jsonPath("$.activeSessionsList[0].connectivityStatus").value("CONNECTED"))
                .andExpect(jsonPath("$.activeSessionsList[0].simulation.scenarioId").value("severe_osa"))
                .andExpect(jsonPath("$.activeSessionsList[0].simulation.status").value("RUNNING"))
                .andExpect(jsonPath("$.activeSessionsList[0].latestPrediction.predictionStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.activeSessionsList[1].sessionCode").value("SES-DSH-02"))
                .andExpect(jsonPath("$.activeSessionsList[1].connectivityStatus").value("STALE"))
                .andExpect(jsonPath("$.activeSessionsList[1].simulation").value(nullValue()))
                .andExpect(jsonPath("$.activeSessionsList[1].latestPrediction.predictionStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.activeSessionsList[1].latestPrediction.inputQualityStatus").value("DEGRADED"))
                // recentPatients ordered createdAt desc: p4, p3, p2, p1
                .andExpect(jsonPath("$.recentPatients.length()").value(4))
                .andExpect(jsonPath("$.recentPatients[0].patientCode").value("PT-DSH004"))
                .andExpect(jsonPath("$.recentPatients[0].reviewStatus").value("NONE"))
                .andExpect(jsonPath("$.recentPatients[1].patientCode").value("PT-DSH003"))
                .andExpect(jsonPath("$.recentPatients[1].reviewStatus").value("NONE"))
                .andExpect(jsonPath("$.recentPatients[2].patientCode").value("PT-DSH002"))
                .andExpect(jsonPath("$.recentPatients[2].reviewStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.recentPatients[3].patientCode").value("PT-DSH001"))
                .andExpect(jsonPath("$.recentPatients[3].reviewStatus").value("NEEDS_REVIEW"));

        // Confirm PatientService.list also populates reviewStatus cleanly (Decision D3)
        mockMvc.perform(operator.with(get("/api/v1/patients?sort=patientCode&direction=asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].patientCode").value("PT-DSH001"))
                .andExpect(jsonPath("$.content[0].reviewStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.content[1].patientCode").value("PT-DSH002"))
                .andExpect(jsonPath("$.content[1].reviewStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.content[2].patientCode").value("PT-DSH003"))
                .andExpect(jsonPath("$.content[2].reviewStatus").value("NONE"))
                .andExpect(jsonPath("$.content[3].patientCode").value("PT-DSH004"))
                .andExpect(jsonPath("$.content[3].reviewStatus").value("NONE"));

        // Verify read-only summary never inserted/modified predictions or jobs
        assertThat(predictionRepository.count()).isEqualTo(7);
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    void truncatesActiveSessionsOverLimitAndMaintainsFixedQueryBudget() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        for (int i = 0; i < 55; i++) {
            String suffix = String.format("%03d", i);
            Patient p = createPatient("PT-BULK" + suffix, "Bulk Patient " + suffix, null,
                    SourceType.SIMULATED, PatientStatus.ACTIVE, now.minusSeconds(600L - i));
            Device d = createDevice("DEV-BK" + suffix, SourceType.SIMULATED, true, now.minusSeconds(5));
            DeviceAssignment a = assignmentRepository.save(new DeviceAssignment(d.getId(), p.getId(), adminUser.getId()));
            MonitoringSession s = sessionRepository.save(
                    new MonitoringSession("SES-BK-" + suffix, p.getId(), a.getId(), adminUser.getId(), now.minusSeconds(300L - i)));
            if (i % 10 == 0) {
                predictionRepository.save(new Prediction(s.getId(), MODEL_VERSION, now.minusSeconds(120), now.minusSeconds(60),
                        PredictedClass.APNEA_EVENT, new BigDecimal("0.850000"),
                        PredictionStatus.NEEDS_REVIEW, InputQualityStatus.VALID, now.minusSeconds(59), 10, null));
            }
        }

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            var summary = dashboardService.getSummary();
            long queryCount = stats.getPrepareStatementCount();
            log.info("Measured DashboardService SQL prepared statements (55 active sessions): {}", queryCount);

            assertThat(summary.summary().patientCount()).isEqualTo(55L);
            assertThat(summary.summary().activeSessions()).isEqualTo(55L);
            assertThat(summary.summary().activeSessionsReturned()).isEqualTo(50);
            assertThat(summary.summary().activeSessionsTruncated()).isTrue();
            assertThat(summary.summary().itemsNeedingReview()).isEqualTo(6L);
            assertThat(summary.activeSessionsList()).hasSize(50);
            assertThat(summary.recentPatients()).hasSize(4);
            assertThat(queryCount).as("Fixed O(1) SQL statement budget for 55 active sessions")
                    .isLessThanOrEqualTo(10L);
        } finally {
            stats.setStatisticsEnabled(previouslyEnabled);
        }
    }

    private Patient createPatient(String code, String name, String alias, SourceType source,
                                  PatientStatus status, Instant createdAt) {
        Patient p = new Patient(code, name, alias, null, null, source, adminUser.getId());
        if (status == PatientStatus.INACTIVE) {
            p.changeStatus(PatientStatus.INACTIVE);
        }
        setField(p, "createdAt", createdAt);
        return patientRepository.save(p);
    }

    private Device createDevice(String code, SourceType type, boolean active, Instant lastSeenAt) {
        Device d = new Device(code, type, "OxiSim", UUID.randomUUID().toString().substring(0, 8), adminUser.getId());
        if (!active) {
            d.deactivate();
        }
        if (lastSeenAt != null) {
            setField(d, "lastSeenAt", lastSeenAt);
        }
        return deviceRepository.save(d);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed setting field " + fieldName, ex);
        }
    }
}
