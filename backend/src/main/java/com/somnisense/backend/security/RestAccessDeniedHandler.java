package com.somnisense.backend.security;

import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.common.web.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Called by Spring Security when the user IS logged in but lacks permission
 * (e.g. an OPERATOR calling an ADMIN-only URL). Returns 403 with our standard ApiError JSON.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorWriter apiErrorWriter;

    public RestAccessDeniedHandler(ApiErrorWriter apiErrorWriter) {
        this.apiErrorWriter = apiErrorWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        // A missing/wrong CSRF token is also an "access denied" in Spring Security; give it its own code
        // so the frontend knows to fetch a fresh token instead of showing "no permission".
        ErrorCode code = (accessDeniedException instanceof CsrfException)
                ? ErrorCode.CSRF_TOKEN_INVALID
                : ErrorCode.FORBIDDEN;
        apiErrorWriter.write(request, response, code);
    }
}
