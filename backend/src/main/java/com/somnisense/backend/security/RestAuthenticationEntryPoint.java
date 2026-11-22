package com.somnisense.backend.security;

import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.common.web.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Called by Spring Security when a request needs a logged-in user but has none (or bad credentials).
 * Returns 401 with our standard ApiError JSON instead of a browser login popup or HTML page.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter apiErrorWriter;

    public RestAuthenticationEntryPoint(ApiErrorWriter apiErrorWriter) {
        this.apiErrorWriter = apiErrorWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        apiErrorWriter.write(request, response, ErrorCode.UNAUTHORIZED);
    }
}
