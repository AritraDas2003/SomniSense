package com.somnisense.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Makes sure the XSRF-TOKEN cookie is always present for the browser.
 *
 * Spring Security creates CSRF tokens lazily: the cookie is only written when something reads the token.
 * This filter reads it on every request, so the React app always has a token to send back in the
 * X-XSRF-TOKEN header. (Pattern recommended by the Spring Security docs for single-page apps.)
 *
 * Not a @Component on purpose: it is added to the Spring Security filter chain in SecurityConfig only;
 * as a @Component, Spring Boot would ALSO register it as a normal servlet filter and it would run twice.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();   // reading it forces the cookie to be written
        }
        filterChain.doFilter(request, response);
    }
}
