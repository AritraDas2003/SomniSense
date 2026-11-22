package com.somnisense.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// @ActiveProfiles("test") loads application-test.properties, which points at somnisense_test_db.
@SpringBootTest
@ActiveProfiles("test")
class BackendApplicationTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	@Test
	void testsRunAgainstTheTestDatabase() {
		String db = jdbcTemplate.queryForObject("select current_database()", String.class);
		assertEquals("somnisense_test_db", db);
	}

	@Test
	void flywayCreatedExactlyTheTenApprovedTables() {
		List<String> tables = jdbcTemplate.queryForList(
				"select table_name from information_schema.tables "
						+ "where table_schema = 'public' and table_name <> 'flyway_schema_history' "
						+ "order by table_name",
				String.class);
		assertEquals(List.of(
				"audit_logs", "device_assignments", "devices", "monitoring_sessions",
				"patient_contacts", "patient_medical_details", "patients", "predictions",
				"sensor_readings", "users"), tables);
	}
}
