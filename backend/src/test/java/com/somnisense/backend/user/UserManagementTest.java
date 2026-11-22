package com.somnisense.backend.user;

import com.somnisense.backend.support.TestDatabase;
import com.jayway.jsonpath.JsonPath;
import com.somnisense.backend.support.LoggedInClient;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 4: Admin staff management through the real HTTP endpoints, against somnisense_test_db. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserManagementTest {

    private static final String ADMIN_PW = "admin-test-password-1";
    private static final String OPERATOR_PW = "operator-test-password-1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User admin;
    private User operator;
    private LoggedInClient adminClient;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(jdbcTemplate);
        admin = userRepository.save(new User("admin", "admin@test.local", passwordEncoder.encode(ADMIN_PW), Role.ADMIN));
        operator = userRepository.save(new User("operator1", "op1@test.local", passwordEncoder.encode(OPERATOR_PW), Role.OPERATOR));
        adminClient = LoggedInClient.login(mockMvc, "admin", ADMIN_PW);
    }

    private static String json(String username, String email, String role) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
    }

    private MvcResult createUser(String username, String email, String role) throws Exception {
        return mockMvc.perform(adminClient.with(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(username, email, role)))
                .andReturn();
    }

    private int auditCount(String action) {
        return jdbcTemplate.queryForObject("select count(*) from audit_logs where action = ?", Integer.class, action);
    }

    // ------------------------------------------------------------------ access control

    @Test
    void operatorCannotUseStaffManagement() throws Exception {
        LoggedInClient op = LoggedInClient.login(mockMvc, "operator1", OPERATOR_PW);
        mockMvc.perform(op.with(get("/api/v1/users"))).andExpect(status().isForbidden());
        mockMvc.perform(op.with(post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                        .content(json("sneaky", "s@test.local", "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(userRepository.existsByUsername("sneaky")).isFalse();
    }

    @Test
    void notLoggedInGets401() throws Exception {
        mockMvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ create

    @Test
    void adminCreatesStaffWithOneTimePassword() throws Exception {
        MvcResult result = mockMvc.perform(adminClient.with(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Nurse.Kim", "Kim@Clinic.Local", "OPERATOR")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/users/")))
                .andExpect(jsonPath("$.user.username").value("nurse.kim"))
                .andExpect(jsonPath("$.user.email").value("kim@clinic.local"))
                .andExpect(jsonPath("$.user.role").value("OPERATOR"))
                .andExpect(jsonPath("$.user.active").value(true))
                .andExpect(jsonPath("$.temporaryPassword").value(matchesPattern("^[A-Za-z0-9]{16}$")))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String tempPw = JsonPath.read(body, "$.temporaryPassword");
        String newId = JsonPath.read(body, "$.user.id");
        assertThat(result.getResponse().getHeader("Location")).endsWith(newId);

        // stored only as a BCrypt hash
        User stored = userRepository.findById(UUID.fromString(newId)).orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2").doesNotContain(tempPw);
        // the new staff member can log in with it
        LoggedInClient.login(mockMvc, "nurse.kim", tempPw);
        // audited: actor = admin, entity = new user
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "select user_id, entity_id, details->>'role' as role from audit_logs where action = 'USER_CREATED'");
        assertThat(audit.get("user_id")).isEqualTo(admin.getId());
        assertThat(audit.get("entity_id")).isEqualTo(UUID.fromString(newId));
        assertThat(audit.get("role")).isEqualTo("OPERATOR");
    }

    @Test
    void duplicateUsernameOrEmailIsConflict() throws Exception {
        MvcResult sameName = createUser("OPERATOR1", "other@test.local", "OPERATOR");
        assertThat(sameName.getResponse().getStatus()).isEqualTo(409);
        assertThat(sameName.getResponse().getContentAsString()).contains("Username is already taken");

        MvcResult sameEmail = createUser("someone", "OP1@test.local", "OPERATOR");
        assertThat(sameEmail.getResponse().getStatus()).isEqualTo(409);
        assertThat(sameEmail.getResponse().getContentAsString()).contains("Email is already in use");
    }

    @Test
    void invalidFieldsAreReportedPerField() throws Exception {
        mockMvc.perform(adminClient.with(post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a@b\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='username')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='email')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='role')]").exists());
    }

    @Test
    void unknownRoleValueIsMalformed() throws Exception {
        MvcResult result = createUser("newbie", "newbie@test.local", "SUPERUSER");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("MALFORMED_REQUEST");
    }

    // ------------------------------------------------------------------ list / get

    @Test
    void listSupportsFiltersPagingAndSorting() throws Exception {
        createUser("zeta", "zeta@test.local", "OPERATOR");
        createUser("alpha", "alpha@test.local", "OPERATOR");
        createUser("boss", "boss@test.local", "ADMIN");

        mockMvc.perform(adminClient.with(get("/api/v1/users"))
                        .param("role", "OPERATOR").param("size", "2").param("sort", "username").param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))   // operator1, zeta, alpha
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].username").value("alpha"))
                .andExpect(jsonPath("$.content[1].username").value("operator1"))
                .andExpect(content().string(not(containsString("password"))));

        mockMvc.perform(adminClient.with(get("/api/v1/users")).param("q", "ZET"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].username").value("zeta"));

        mockMvc.perform(adminClient.with(get("/api/v1/users")).param("q", "%"))   // % is literal, matches nothing
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listRejectsBadPagingParameters() throws Exception {
        mockMvc.perform(adminClient.with(get("/api/v1/users")).param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(adminClient.with(get("/api/v1/users")).param("sort", "passwordHash"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void getByIdAndNotFound() throws Exception {
        mockMvc.perform(adminClient.with(get("/api/v1/users/" + operator.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("operator1"));
        mockMvc.perform(adminClient.with(get("/api/v1/users/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(adminClient.with(get("/api/v1/users/not-a-uuid")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ role changes

    @Test
    void changeRoleIsAuditedAndRepeatingItIsANoOp() throws Exception {
        String url = "/api/v1/users/" + operator.getId() + "/role";
        mockMvc.perform(adminClient.with(patch(url)).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
        mockMvc.perform(adminClient.with(patch(url)).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        assertThat(auditCount("USER_ROLE_CHANGED")).isEqualTo(1);
        Map<String, Object> details = jdbcTemplate.queryForMap(
                "select details->>'from' as f, details->>'to' as t from audit_logs where action = 'USER_ROLE_CHANGED'");
        assertThat(details).containsEntry("f", "OPERATOR").containsEntry("t", "ADMIN");
    }

    @Test
    void roleChangeEndsTheAffectedUsersSession() throws Exception {
        LoggedInClient op = LoggedInClient.login(mockMvc, "operator1", OPERATOR_PW);
        mockMvc.perform(adminClient.with(patch("/api/v1/users/" + operator.getId() + "/role"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"));
        mockMvc.perform(op.with(get("/api/v1/auth/me")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    // ------------------------------------------------------------------ deactivate / reactivate

    @Test
    void deactivateBlocksLoginAndEndsSessionReactivateRestores() throws Exception {
        LoggedInClient op = LoggedInClient.login(mockMvc, "operator1", OPERATOR_PW);

        mockMvc.perform(adminClient.with(post("/api/v1/users/" + operator.getId() + "/deactivate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(adminClient.with(post("/api/v1/users/" + operator.getId() + "/deactivate")))
                .andExpect(status().isOk());   // repeat = no-op

        mockMvc.perform(op.with(get("/api/v1/auth/me"))).andExpect(jsonPath("$.code").value("SESSION_INVALID"));
        assertThat(LoggedInClient.attemptLogin(mockMvc, "operator1", OPERATOR_PW).getResponse().getStatus()).isEqualTo(401);

        mockMvc.perform(adminClient.with(post("/api/v1/users/" + operator.getId() + "/reactivate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        LoggedInClient.login(mockMvc, "operator1", OPERATOR_PW);

        assertThat(auditCount("USER_DEACTIVATED")).isEqualTo(1);
        assertThat(auditCount("USER_REACTIVATED")).isEqualTo(1);
    }

    @Test
    void updatedAtInTheResponseReflectsTheChangeButLoginDoesNotTouchIt() throws Exception {
        String before = operator.getUpdatedAt().toString();
        MvcResult result = mockMvc.perform(adminClient.with(post("/api/v1/users/" + operator.getId() + "/deactivate")))
                .andExpect(status().isOk())
                .andReturn();
        String inResponse = JsonPath.read(result.getResponse().getContentAsString(), "$.updatedAt");
        assertThat(java.time.Instant.parse(inResponse)).isAfter(java.time.Instant.parse(before));

        // admin logged in during setUp: last_login_at is set but updated_at is unchanged
        User reloadedAdmin = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(reloadedAdmin.getLastLoginAt()).isNotNull();
        assertThat(reloadedAdmin.getUpdatedAt()).isEqualTo(reloadedAdmin.getCreatedAt());
    }

    @Test
    void adminCannotActOnTheirOwnAccount() throws Exception {
        String self = "/api/v1/users/" + admin.getId();
        mockMvc.perform(adminClient.with(post(self + "/deactivate")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_ACTION_NOT_ALLOWED"));
        mockMvc.perform(adminClient.with(patch(self + "/role")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR\"}"))
                .andExpect(jsonPath("$.code").value("SELF_ACTION_NOT_ALLOWED"));
        mockMvc.perform(adminClient.with(post(self + "/reset-password")))
                .andExpect(jsonPath("$.code").value("SELF_ACTION_NOT_ALLOWED"));
        assertThat(userRepository.findById(admin.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void anotherAdminMayBeDeactivatedWhileOneActiveAdminRemains() throws Exception {
        User second = userRepository.save(new User("admin2", "admin2@test.local", passwordEncoder.encode(ADMIN_PW), Role.ADMIN));
        mockMvc.perform(adminClient.with(post("/api/v1/users/" + second.getId() + "/deactivate")))
                .andExpect(status().isOk());
        assertThat(userRepository.countByRoleAndActiveTrue(Role.ADMIN)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ passwords

    @Test
    void adminResetIssuesNewPasswordAndEndsTheUsersSessions() throws Exception {
        LoggedInClient op = LoggedInClient.login(mockMvc, "operator1", OPERATOR_PW);

        MvcResult reset = mockMvc.perform(adminClient.with(post("/api/v1/users/" + operator.getId() + "/reset-password")))
                .andExpect(status().isOk())
                .andReturn();
        String tempPw = JsonPath.read(reset.getResponse().getContentAsString(), "$.temporaryPassword");

        mockMvc.perform(op.with(get("/api/v1/auth/me"))).andExpect(jsonPath("$.code").value("SESSION_INVALID"));
        assertThat(LoggedInClient.attemptLogin(mockMvc, "operator1", OPERATOR_PW).getResponse().getStatus()).isEqualTo(401);
        LoggedInClient.login(mockMvc, "operator1", tempPw);
        assertThat(auditCount("USER_PASSWORD_RESET")).isEqualTo(1);
    }

    @Test
    void staffChangeTheirOwnPassword() throws Exception {
        LoggedInClient op = LoggedInClient.login(mockMvc, "operator1", OPERATOR_PW);
        LoggedInClient opOtherBrowser = LoggedInClient.login(mockMvc, "operator1", OPERATOR_PW);

        mockMvc.perform(op.with(post("/api/v1/auth/password")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-one-123\",\"newPassword\":\"brand-new-password-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));
        mockMvc.perform(op.with(post("/api/v1/auth/password")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + OPERATOR_PW + "\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));

        mockMvc.perform(op.with(post("/api/v1/auth/password")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + OPERATOR_PW + "\",\"newPassword\":\"brand-new-password-1\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(op.with(get("/api/v1/auth/me"))).andExpect(status().isOk());   // this session stays
        mockMvc.perform(opOtherBrowser.with(get("/api/v1/auth/me")))                     // other session ends
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
        LoggedInClient.login(mockMvc, "operator1", "brand-new-password-1");
        assertThat(auditCount("PASSWORD_CHANGED")).isEqualTo(1);
    }

    @Test
    void responsesNeverContainPasswordHashes() throws Exception {
        mockMvc.perform(adminClient.with(get("/api/v1/users/" + operator.getId())))
                .andExpect(content().string(not(containsString("$2a$"))))
                .andExpect(content().string(not(containsString("passwordHash"))));
        mockMvc.perform(adminClient.with(get("/api/v1/users")))
                .andExpect(content().string(not(containsString("$2a$"))));
    }
}
