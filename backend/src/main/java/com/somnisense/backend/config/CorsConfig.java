package com.somnisense.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS = which OTHER websites (origins) may call this API from a browser.
 *
 * The React dev server (http://localhost:5173) is a different origin from the API (http://localhost:8080),
 * so the browser blocks its requests unless we list it here. Because we use a session cookie,
 * allowCredentials is true, and that is only safe with an exact list of origins - never "*".
 *
 * Origins come from somnisense.cors.allowed-origins (env var CORS_ALLOWED_ORIGINS, comma-separated).
 * SecurityConfig enables this with http.cors(...).
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${somnisense.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "X-Correlation-Id"));
        config.setExposedHeaders(List.of("X-Correlation-Id"));   // lets JavaScript read this response header
        config.setAllowCredentials(true);                       // send the session cookie
        config.setMaxAge(3600L);                                 // browser may cache the preflight for 1 hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
