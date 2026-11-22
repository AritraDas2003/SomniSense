package com.somnisense.backend.support;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Test helper: logs in through the real endpoints (CSRF cookie -> login) and then decorates later requests
 * with the session cookie and CSRF header, exactly like the browser would.
 *
 * Usage:  LoggedInClient admin = LoggedInClient.login(mockMvc, "admin", "pw");
 *         mockMvc.perform(admin.with(post("/api/v1/users").content(...)))
 */
public final class LoggedInClient {

    private final MockHttpSession session;
    private final Cookie xsrf;

    private LoggedInClient(MockHttpSession session, Cookie xsrf) {
        this.session = session;
        this.xsrf = xsrf;
    }

    public static LoggedInClient login(MockMvc mockMvc, String login, String password) throws Exception {
        MvcResult result = attemptLogin(mockMvc, login, password);
        assertThat(result.getResponse().getStatus()).as("login status for " + login).isEqualTo(200);
        return new LoggedInClient((MockHttpSession) result.getRequest().getSession(false), latestXsrfCookie(result));
    }

    /** Performs a login and returns the raw result (for tests that expect a failure). */
    public static MvcResult attemptLogin(MockMvc mockMvc, String login, String password) throws Exception {
        Cookie xsrf = latestXsrfCookie(mockMvc.perform(get("/api/v1/auth/csrf")).andReturn());
        return mockMvc.perform(post("/api/v1/auth/login")
                        .session(new MockHttpSession())
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    /** Adds this client's session cookie and CSRF header to a request. */
    public MockHttpServletRequestBuilder with(MockHttpServletRequestBuilder request) {
        return request.session(session).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
    }

    public MockHttpSession session() {
        return session;
    }

    private static Cookie latestXsrfCookie(MvcResult result) {
        List<Cookie> cookies = Arrays.stream(result.getResponse().getCookies())
                .filter(c -> c.getName().equals("XSRF-TOKEN") && c.getMaxAge() != 0)
                .toList();
        assertThat(cookies).as("XSRF-TOKEN cookie").isNotEmpty();
        return cookies.get(cookies.size() - 1);
    }
}
