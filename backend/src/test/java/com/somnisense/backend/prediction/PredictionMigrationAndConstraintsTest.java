package com.somnisense.backend.prediction;

import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Flyway V5 migration artifacts, prediction unique/lookup indexes, and simulation_runs constraints.
 */
@SpringBootTest
@ActiveProfiles("test")
class PredictionMigrationAndConstraintsTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
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

    private MonitoringSession session;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        User admin = userRepository.save(new User("admin", "admin@test.local",
                passwordEncoder.encode("a-test-password-123"), Role.ADMIN));
        UUID by = admin.getId();
        Patient patient = patientRepository.save(new Patient("PT-V5TEST01", "V5 Patient", null, null, null,
                SourceType.SIMULATED, by));
        Device device = deviceRepository.save(new Device("DEV-V5TEST1", SourceType.SIMULATED, null, null, by));
        DeviceAssignment assignment = assignmentRepository.save(new DeviceAssignment(device.getId(), patient.getId(), by));
        session = sessionRepository.save(new MonitoringSession("SES-V5TEST-001", patient.getId(),
                assignment.getId(), by, Instant.now().minusSeconds(120)));
    }

    @Test
    void flywayAppliedV5AndCreatedRequiredIndexes() {
        List<String> versions = jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success = true order by installed_rank",
                String.class);
        assertThat(versions).contains("1", "2", "3", "4", "5");

        List<String> predictionIndexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where schemaname = 'public' and tablename = 'predictions'",
                String.class);
        assertThat(predictionIndexes).contains("ux_predictions_session_model_window", "ix_predictions_session_window_end");

        List<String> simRunIndexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where schemaname = 'public' and tablename = 'simulation_runs'",
                String.class);
        assertThat(simRunIndexes).contains("ix_simulation_runs_status");
    }

    @Test
    void uniquePredictionIndexPreventsDuplicateSessionModelWindowRows() {
        String insert = "insert into predictions (id, session_id, model_version, window_start, window_end, "
                + "predicted_class, confidence_score, prediction_status, input_quality_status, processed_at, "
                + "processing_time_ms, error_message) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        Instant wStart = Instant.parse("2026-11-26T00:00:00Z");
        Instant wEnd = Instant.parse("2026-11-26T00:00:30Z");

        jdbcTemplate.update(insert, UUID.randomUUID(), session.getId(), "phase7_model_c:1.0.0",
                java.sql.Timestamp.from(wStart), java.sql.Timestamp.from(wEnd),
                "NORMAL", new java.math.BigDecimal("0.120000"), "COMPLETED", "VALID",
                java.sql.Timestamp.from(wEnd.plusSeconds(1)), 15, null);

        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), session.getId(), "phase7_model_c:1.0.0",
                java.sql.Timestamp.from(wStart), java.sql.Timestamp.from(wEnd),
                "APNEA_EVENT", new java.math.BigDecimal("0.910000"), "NEEDS_REVIEW", "VALID",
                java.sql.Timestamp.from(wEnd.plusSeconds(2)), 20, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_predictions_session_model_window");

        // Different model version for the same window is allowed
        jdbcTemplate.update(insert, UUID.randomUUID(), session.getId(), "phase7_model_c:1.1.0",
                java.sql.Timestamp.from(wStart), java.sql.Timestamp.from(wEnd),
                "NORMAL", new java.math.BigDecimal("0.100000"), "COMPLETED", "VALID",
                java.sql.Timestamp.from(wEnd.plusSeconds(2)), 14, null);

        assertThat(jdbcTemplate.queryForObject("select count(*) from predictions", Integer.class)).isEqualTo(2);
    }

    @Test
    void simulationRunsEnforcesForeignKeyUniquenessAndCheckConstraints() {
        String insert = "insert into simulation_runs (id, session_id, scenario_id, seed, cadence_hz, playback_speed, "
                + "quality_preset, status, last_step, started_at, updated_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        Instant t0 = Instant.parse("2026-11-26T00:00:00Z");

        // Nonexistent session rejected by FK
        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), UUID.randomUUID(), "S1_NORMAL",
                42L, 1.00, 1.00, null, "PENDING", 0L, java.sql.Timestamp.from(t0), java.sql.Timestamp.from(t0)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Valid row succeeds (PAUSED respects V6 chk_simulation_runs_running_has_lease)
        jdbcTemplate.update(insert, UUID.randomUUID(), session.getId(), "S1_NORMAL",
                42L, 1.00, 1.00, "CLEAN", "PAUSED", 10L, java.sql.Timestamp.from(t0), java.sql.Timestamp.from(t0.plusSeconds(10)));

        // Second simulation_runs row for same session rejected by UNIQUE constraint
        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), session.getId(), "S2_APNEA",
                43L, 1.00, 1.00, null, "PENDING", 0L, java.sql.Timestamp.from(t0), java.sql.Timestamp.from(t0)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Invalid status / negative step / zero cadence / updated_at < started_at rejected by CHECK constraints
        jdbcTemplate.update("delete from simulation_runs where session_id = ?", session.getId());

        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), session.getId(), "S1_NORMAL",
                42L, 1.00, 1.00, null, "BOGUS", 0L, java.sql.Timestamp.from(t0), java.sql.Timestamp.from(t0)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), session.getId(), "S1_NORMAL",
                42L, 0.00, 1.00, null, "PENDING", 0L, java.sql.Timestamp.from(t0), java.sql.Timestamp.from(t0)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), session.getId(), "S1_NORMAL",
                42L, 1.00, 1.00, null, "PENDING", -1L, java.sql.Timestamp.from(t0), java.sql.Timestamp.from(t0)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), session.getId(), "S1_NORMAL",
                42L, 1.00, 1.00, null, "PENDING", 0L, java.sql.Timestamp.from(t0), java.sql.Timestamp.from(t0.minusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
