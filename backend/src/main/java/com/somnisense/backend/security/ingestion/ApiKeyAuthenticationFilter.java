package com.somnisense.backend.security.ingestion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates ingestion requests by the X-Ingestion-Key header.
 * A correct key -> the request is treated as the "ingestion-client" with role INGESTION.
 * A missing/wrong key -> nothing is set, so the authorization rule in IngestionSecurityConfig answers 401.
 *
 * Not a @Component: it is added only to the ingestion security chain.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL = "ingestion-client";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final IngestionApiKey apiKey;

    public ApiKeyAuthenticationFilter(IngestionApiKey apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(IngestionApiKey.HEADER);
        if (apiKey.matches(presented)) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_INGESTION"))));
            SecurityContextHolder.setContext(context);
        } else if (presented != null) {
            log.warn("Rejected ingestion request with an invalid key from {}", request.getRemoteAddr());   // key never logged
        }
        chain.doFilter(request, response);
    }
}
