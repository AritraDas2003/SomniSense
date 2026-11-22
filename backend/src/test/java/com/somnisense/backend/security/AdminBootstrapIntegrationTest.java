package com.somnisense.backend.security;

import com.somnisense.backend.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs AdminBootstrap against the REAL test database (no mocks).
 * Added after a bug the mock-based test could not catch: the ADMIN_BOOTSTRAPPED audit row was written in a
 * separate transaction that could not see the new (uncommitted) user, so the foreign key failed at startup.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "somnisense.bootstrap-admin.enabled=true",
        "SOMNISENSE_ADMIN_USERNAME=bootadmin",
        "SOMNISENSE_ADMIN_EMAIL=bootadmin@test.local",
        "SOMNISENSE_ADMIN_PASSWORD=bootstrap-test-password"
})
class AdminBootstrapIntegrationTest {

    @Autowired
    private AdminBootstrap adminBootstrap;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void emptyTables() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void createsAdminAndAuditRowTogether() {
        adminBootstrap.run(null);

        Map<String, Object> user = jdbcTemplate.queryForMap(
                "select id, username, role, is_active, password_hash from users");
        assertThat(user.get("username")).isEqualTo("bootadmin");
        assertThat(user.get("role")).isEqualTo("ADMIN");
        assertThat(user.get("is_active")).isEqualTo(true);
        assertThat(user.get("password_hash").toString()).startsWith("$2");

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "select user_id, entity_id, details->>'role' as role from audit_logs where action = 'ADMIN_BOOTSTRAPPED'");
        assertThat(audit.get("user_id")).isEqualTo(user.get("id"));
        assertThat(audit.get("role")).isEqualTo("ADMIN");
    }

    @Test
    void secondRunChangesNothing() {
        adminBootstrap.run(null);
        adminBootstrap.run(null);
        assertThat(jdbcTemplate.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
    }
}
