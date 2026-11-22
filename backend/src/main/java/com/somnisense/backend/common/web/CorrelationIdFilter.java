package com.somnisense.backend.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every HTTP request a "correlation ID" so one request can be traced across logs and error responses.
 *
 * - If the caller sends a safe X-Correlation-Id header, we reuse it; otherwise we generate a UUID.
 * - The ID is returned in the X-Correlation-Id response header and inside every ApiError.
 * - It is put into the logging MDC ("Mapped Diagnostic Context"), so every log line written while
 *   handling this request shows it (see logging.pattern.level in application.properties).
 *
 * Annotations:
 * - @Component: Spring creates this object and, because it is a Filter, registers it with the web server.
 * - @Order(HIGHEST_PRECEDENCE): run before every other filter, including Spring Security,
 *   so even 401/403 responses carry the ID.
 * - OncePerRequestFilter: Spring base class that guarantees doFilterInternal runs once per request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    /** Name of the request attribute where the ID is stored for the rest of the request. */
    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

    // Only accept short, simple IDs from callers - prevents log injection and absurdly long headers.
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER_NAME);
        String correlationId = (incoming != null && SAFE_ID.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString();

        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            filterChain.doFilter(request, response);   // continue to the next filter / controller
        } finally {
            MDC.remove(MDC_KEY);   // threads are reused, so always clean up
        }
    }

    /** Returns the ID for this request (or null if the filter did not run, e.g. in some unit tests). */
    public static String currentId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
