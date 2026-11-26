package com.somnisense.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Empties every application table in the TEST database (somnisense_test_db) before a test.
 *
 * One TRUNCATE listing all 12 approved tables at once, so foreign keys between them never get in the way
 * (deleting "users" alone fails once patients reference them). flyway_schema_history is NOT touched, so the
 * schema itself stays in place. Never point this at the development database.
 */
public final class TestDatabase {

    private TestDatabase() {
    }

    public static void clean(JdbcTemplate jdbcTemplate) {
        String db = jdbcTemplate.queryForObject("select current_database()", String.class);
        if (!"somnisense_test_db".equals(db)) {
            throw new IllegalStateException("Refusing to clean non-test database: " + db);
        }
        jdbcTemplate.execute("TRUNCATE TABLE sensor_readings, predictions, inference_window_jobs, simulation_runs, "
                + "monitoring_sessions, device_assignments, devices, patient_medical_details, patient_contacts, "
                + "patients, audit_logs, users");
    }
}
