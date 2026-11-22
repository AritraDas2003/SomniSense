package com.somnisense.backend.security;

import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.common.web.ApiErrorWriter;
import com.somnisense.backend.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * The security rules of the whole API (Stage 3: session-cookie login, decision 2026-11-21).
 *
 * How a request is checked, in order:
 * 1. CORS        - only the listed frontend origins may call us from a browser (CorsConfig).
 * 2. Session     - the JSESSIONID cookie identifies the logged-in user (stored server-side).
 * 3. CSRF        - every state-changing request (POST/PUT/PATCH/DELETE) must send the X-XSRF-TOKEN header
 *                  matching the XSRF-TOKEN cookie. This stops other websites from using the cookie.
 * 4. Active user - ActiveUserCheckFilter ends sessions of deactivated users or changed roles.
 * 5. URL rules   - authorizeHttpRequests below: public routes, ADMIN-only routes, everything else = logged in.
 *
 * @EnableMethodSecurity additionally allows @PreAuthorize("hasRole('ADMIN')") on service/controller methods.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final AuditLogoutHandler auditLogoutHandler;
    private final boolean secureCookies;

    public SecurityConfig(RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler,
                          AuditLogoutHandler auditLogoutHandler,
                          @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.auditLogoutHandler = auditLogoutHandler;
        this.secureCookies = secureCookies;
    }

    /** @Order(2): the ingestion chain (@Order(1), /api/v1/ingestion/**) is checked first; everything else lands here. */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository securityContextRepository,
                                                   CsrfTokenRepository csrfTokenRepository,
                                                   UserRepository userRepository,
                                                   ApiErrorWriter apiErrorWriter,
                                                   AuditService auditService) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        // Plain handler: the header value must equal the cookie value (simple for axios/Postman).
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                // No browser popups or HTML login pages - this is a JSON API.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/health", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // Spring Boot forwards errors to /error internally; keep the real status visible.
                        .requestMatchers("/error").permitAll()
                        // Staff management (Stage 4) is ADMIN-only. Rule added now so it can never be forgotten.
                        .requestMatchers("/api/v1/users/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")           // POST only, CSRF-protected
                        .addLogoutHandler(auditLogoutHandler)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies("JSESSIONID"))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)   // 401 JSON
                        .accessDeniedHandler(accessDeniedHandler))            // 403 JSON
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterAfter(new ActiveUserCheckFilter(userRepository, apiErrorWriter, auditService),
                        AnonymousAuthenticationFilter.class);

        return http.build();
    }

    /** Checks username/email + password using our StaffUserDetailsService and BCrypt. */
    @Bean
    public AuthenticationManager authenticationManager(StaffUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /** Where the logged-in user is kept between requests: the server-side HTTP session. */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * CSRF token stored in a cookie named XSRF-TOKEN that JavaScript may read (HttpOnly=false), so the
     * frontend can copy it into the X-XSRF-TOKEN header. The session cookie itself stays HttpOnly.
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(secureCookies));
        return repository;
    }
}
