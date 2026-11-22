package com.somnisense.backend.auth;

import com.somnisense.backend.support.TestDatabase;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of Stage 3 login/session/CSRF/role rules against somnisense_test_db.
 * Uses the REAL CSRF cookie flow a browser would: GET /csrf -> cookie -> send it back as X-XSRF-TOKEN.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTest {

    private static final String ADMIN_PASSWORD = "admin-test-password-1";
    private static final String OPERATOR_PASSWORD = "operator-test-password-1";

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

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        admin = userRepository.save(new User("Admin", "Admin@Test.Local", passwordEncoder.encode(ADMIN_PASSWORD), Role.ADMIN));
        operator = userRepository.save(new User("operator1", "op1@test.local", passwordEncoder.encode(OPERATOR_PASSWORD), Role.OPERATOR));
        User inactive = new User("retired", "retired@test.local", passwordEncoder.encode(OPERATOR_PASSWORD), Role.OPERATOR);
        inactive.deactivate();
        userRepository.save(inactive);
    }

    // ------------------------------------------------------------------ helpers

    /** Like a browser: fetch the CSRF cookie first. */
    private Cookie fetchCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn();
        return latestXsrfCookie(result);
    }

    /** The login response replaces the token, so take the LAST non-deleted XSRF-TOKEN cookie. */
    private static Cookie latestXsrfCookie(MvcResult result) {
        List<Cookie> cookies = Arrays.stream(result.getResponse().getCookies())
                .filter(c -> c.getName().equals("XSRF-TOKEN") && c.getMaxAge() != 0)
                .toList();
        assertThat(cookies).as("XSRF-TOKEN cookie").isNotEmpty();
        return cookies.get(cookies.size() - 1);
    }

    private static String loginJson(String login, String password) {
        return "{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}";
    }

    private MvcResult login(String login, String password, MockHttpSession session) throws Exception {
        Cookie xsrf = fetchCsrfCookie();
        return mockMvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(login, password)))
                .andReturn();
    }

    /** Logs in and returns the session that now holds the logged-in user. */
    private MockHttpSession loggedInSession(String login, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult result = login(login, password, session);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    // ------------------------------------------------------------------ login

    @Test
    void loginWithUsernameReturnsCurrentUserWithoutPasswordHash() throws Exception {
        MvcResult result = login("admin", ADMIN_PASSWORD, new MockHttpSession());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"username\":\"admin\"", "\"role\":\"ADMIN\"", "\"email\":\"admin@test.local\"");
        assertThat(body).doesNotContain("password", "$2a$");
    }

    @Test
    void loginWithEmailIsCaseInsensitive() throws Exception {
        MvcResult result = login("  OP1@TEST.LOCAL ", OPERATOR_PASSWORD, new MockHttpSession());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"role\":\"OPERATOR\"");
    }

    @Test
    void wrongPasswordUnknownUserAndInactiveAccountAllLookIdentical() throws Exception {
        for (String[] attempt : new String[][]{
                {"admin", "wrong-password-123"},
                {"nobody-here", "whatever-password"},
                {"retired", OPERATOR_PASSWORD}}) {
            MvcResult result = login(attempt[0], attempt[1], new MockHttpSession());
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getContentAsString())
                    .contains("\"code\":\"INVALID_CREDENTIALS\"")
                    .contains("\"message\":\"Invalid username/email or password\"");
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select user_id, details->>'reason' as reason, details::text as raw from audit_logs "
                        + "where action = 'LOGIN_FAILED' order by created_at");
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("reason"))
                .containsExactly("BAD_CREDENTIALS", "BAD_CREDENTIALS", "ACCOUNT_DISABLED");
        assertThat(rows.get(0).get("user_id")).isEqualTo(admin.getId());   // linked to the real account
        assertThat(rows.get(1).get("user_id")).isNull();                   // unknown login: nothing linked
        assertThat(rows).allSatisfy(r -> assertThat(r.get("raw").toString())
                .doesNotContain("nobody-here", "wrong-password"));
    }

    @Test
    void successfulLoginUpdatesLastLoginAndIsAudited() throws Exception {
        login("admin", ADMIN_PASSWORD, new MockHttpSession());

        User reloaded = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(reloaded.getLastLoginAt()).isNotNull();
        assertThat(reloaded.getPasswordHash()).startsWith("$2");   // BCrypt hash, never plain text
        Integer successes = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'LOGIN_SUCCESS' and user_id = ?",
                Integer.class, admin.getId());
        assertThat(successes).isEqualTo(1);
    }

    @Test
    void loginChangesTheSessionId() throws Exception {
        MockHttpSession before = new MockHttpSession();
        String oldId = before.getId();
        MvcResult result = login("admin", ADMIN_PASSWORD, before);
        assertThat(result.getRequest().getSession(false).getId()).isNotEqualTo(oldId);
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("admin", ADMIN_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void blankLoginFieldsAreValidationErrors() throws Exception {
        Cookie xsrf = fetchCsrfCookie();
        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
    }

    // ------------------------------------------------------------------ /me and logout

    @Test
    void meWithoutLoginIs401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void meReturnsTheLoggedInUser() throws Exception {
        MockHttpSession session = loggedInSession("operator1", OPERATOR_PASSWORD);
        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("operator1"))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.lastLoginAt").exists())
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult loginResult = login("admin", ADMIN_PASSWORD, session);
        MockHttpSession loggedIn = (MockHttpSession) loginResult.getRequest().getSession(false);
        Cookie xsrf = latestXsrfCookie(loginResult);   // token was rotated at login

        mockMvc.perform(post("/api/v1/auth/logout").session(loggedIn)
                        .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").session(loggedIn))
                .andExpect(status().isUnauthorized());
        Integer logouts = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'LOGOUT'", Integer.class);
        assertThat(logouts).isEqualTo(1);
    }

    @Test
    void logoutWithoutCsrfIsRejected() throws Exception {
        MockHttpSession session = loggedInSession("admin", ADMIN_PASSWORD);
        mockMvc.perform(post("/api/v1/auth/logout").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void oldCsrfTokenStopsWorkingAfterLogin() throws Exception {
        Cookie beforeLogin = fetchCsrfCookie();
        MockHttpSession session = new MockHttpSession();
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login").session(session)
                        .cookie(beforeLogin).header("X-XSRF-TOKEN", beforeLogin.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(loginJson("admin", ADMIN_PASSWORD)))
                .andReturn();
        Cookie afterLogin = latestXsrfCookie(loginResult);
        assertThat(afterLogin.getValue()).isNotEqualTo(beforeLogin.getValue());
    }

    // ------------------------------------------------------------------ deactivation / role change

    @Test
    void deactivatedUserIsLoggedOutOnNextRequest() throws Exception {
        MockHttpSession session = loggedInSession("operator1", OPERATOR_PASSWORD);

        User op = userRepository.findById(operator.getId()).orElseThrow();
        op.deactivate();
        userRepository.save(op);

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
        assertThat(session.isInvalid()).isTrue();
        String reason = jdbcTemplate.queryForObject(
                "select details->>'reason' from audit_logs where action = 'SESSION_REVOKED'", String.class);
        assertThat(reason).isEqualTo("ACCOUNT_DEACTIVATED");
    }

    @Test
    void roleChangeForcesLoginAgain() throws Exception {
        MockHttpSession session = loggedInSession("operator1", OPERATOR_PASSWORD);

        User op = userRepository.findById(operator.getId()).orElseThrow();
        op.changeRole(Role.ADMIN);
        userRepository.save(op);

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    // ------------------------------------------------------------------ roles

    @Test
    void operatorIsForbiddenFromAdminRoutes() throws Exception {
        MockHttpSession session = loggedInSession("operator1", OPERATOR_PASSWORD);
        mockMvc.perform(get("/api/v1/users").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminPassesTheAdminRouteRule() throws Exception {
        MockHttpSession session = loggedInSession("admin", ADMIN_PASSWORD);
        // Stage 4 added the /users endpoint, so an Admin now gets the list.
        mockMvc.perform(get("/api/v1/users").session(session))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ CORS

    @Test
    void corsAllowsTheFrontendDevOriginWithCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void corsRejectsUnknownOrigins() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
