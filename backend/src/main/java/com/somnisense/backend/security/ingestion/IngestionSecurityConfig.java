package com.somnisense.backend.security.ingestion;

import com.somnisense.backend.security.RestAccessDeniedHandler;
import com.somnisense.backend.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * A SEPARATE security chain for machine-to-machine reading ingestion (decision F8).
 *
 * @Order(1) + securityMatcher: requests to /api/v1/ingestion/** are handled ONLY by this chain; everything else
 * falls through to the staff chain in SecurityConfig (@Order(2)).
 *
 * Differences from the staff chain, and why:
 * - Authentication by X-Ingestion-Key, NOT by login cookie: a logged-in browser cannot submit readings
 *   (UI plan: "do not expose an unrestricted fake-reading endpoint to browsers").
 * - STATELESS: no HTTP session is ever created for the sender.
 * - CSRF off: CSRF protects cookie-based logins; this chain ignores cookies, so there is nothing to forge.
 * - CORS off: browsers on other sites get no permission to call this path at all.
 */
@Configuration
public class IngestionSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain ingestionFilterChain(HttpSecurity http, IngestionApiKey apiKey,
                                                    RestAuthenticationEntryPoint entryPoint,
                                                    RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .securityMatcher("/api/v1/ingestion/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.requireExplicitSave(true))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INGESTION"))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKey), AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
