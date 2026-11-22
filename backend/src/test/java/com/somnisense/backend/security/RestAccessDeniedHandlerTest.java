package com.somnisense.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No URL needs a role yet (that arrives in Stage 3), so this calls the 403 handler directly
 * to prove the response matches the ApiError shape.
 */
@SpringBootTest
@ActiveProfiles("test")
class RestAccessDeniedHandlerTest {

    @Autowired
    private RestAccessDeniedHandler handler;

    @Test
    void writes403InApiErrorShape() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        String body = response.getContentAsString();
        // fields come out in the documented order
        assertThat(body).startsWith("{\"timestamp\":");
        assertThat(body.indexOf("\"status\"")).isLessThan(body.indexOf("\"code\""));
        assertThat(body.indexOf("\"code\"")).isLessThan(body.indexOf("\"fieldErrors\""));
        assertThat(body).contains("\"code\":\"FORBIDDEN\"");
        assertThat(body).contains("\"path\":\"/api/v1/users\"");
        assertThat(body).contains("\"fieldErrors\":[]");
    }
}
