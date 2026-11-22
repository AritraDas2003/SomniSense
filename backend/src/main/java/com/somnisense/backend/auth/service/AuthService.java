package com.somnisense.backend.auth.service;

import com.somnisense.backend.audit.service.AuditAction;
import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.auth.dto.ChangePasswordRequest;
import com.somnisense.backend.auth.dto.CurrentUserResponse;
import com.somnisense.backend.common.exception.InvalidRequestException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import com.somnisense.backend.auth.dto.LoginRequest;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.security.AuthenticatedUser;
import com.somnisense.backend.security.StaffUserDetailsService;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Login logic for the session-cookie design (decision 2026-11-21).
 *
 * Steps of a successful login:
 * 1. AuthenticationManager checks the password against the BCrypt hash (and that the account is active).
 * 2. The session ID is changed ("session fixation" protection: an attacker who planted a session ID
 *    before login cannot reuse it afterwards).
 * 3. The authenticated user is saved into the HTTP session; the browser keeps the JSESSIONID cookie.
 * 4. The CSRF token is replaced with a fresh one.
 * 5. users.last_login_at is updated and LOGIN_SUCCESS is audited.
 *
 * On failure the client always gets the same 401 INVALID_CREDENTIALS, whatever the reason.
 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final StaffUserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public AuthService(AuthenticationManager authenticationManager,
                       SecurityContextRepository securityContextRepository,
                       CsrfTokenRepository csrfTokenRepository,
                       StaffUserDetailsService userDetailsService,
                       UserRepository userRepository,
                       AuditService auditService,
                       PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
    }

    public CurrentUserResponse login(LoginRequest loginRequest, HttpServletRequest request,
                                     HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(loginRequest.getLogin(),
                            loginRequest.getPassword()));
        } catch (AuthenticationException | IllegalArgumentException ex) {
            // IllegalArgumentException: BCrypt rejects passwords longer than 72 bytes (e.g. many non-ASCII chars).
            recordFailedLogin(loginRequest.getLogin(), ex);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

        // 2. new session ID
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }

        // 3. store the login in the session
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        // 4. fresh CSRF token (old one deleted, new one written to the XSRF-TOKEN cookie)
        csrfTokenRepository.saveToken(null, request, response);
        CsrfToken freshToken = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(freshToken, request, response);
        request.setAttribute(CsrfToken.class.getName(), freshToken);

        // 5. bookkeeping
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
        user.recordLogin(Instant.now());
        userRepository.save(user);
        auditService.record(user.getId(), AuditAction.LOGIN_SUCCESS, "USER", user.getId(), null);

        return CurrentUserResponse.from(user);
    }

    /** The logged-in user's CURRENT data, read fresh from the database. */
    public CurrentUserResponse currentUser(AuthenticatedUser principal) {
        return userRepository.findById(principal.getId())
                .map(CurrentUserResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_INVALID));
    }

    /**
     * "Change my password" for any logged-in staff member.
     * The current password must be correct (400 CURRENT_PASSWORD_INCORRECT otherwise) and the new one different.
     * Afterwards the user's OTHER sessions end (password fingerprint changed), while THIS session is updated
     * with the new fingerprint so the user stays logged in here.
     */
    @Transactional
    public void changeOwnPassword(AuthenticatedUser principal, ChangePasswordRequest body,
                                  HttpServletRequest request, HttpServletResponse response) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_INVALID));
        if (!passwordEncoder.matches(body.getCurrentPassword(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.CURRENT_PASSWORD_INCORRECT);
        }
        if (body.getNewPassword().equals(body.getCurrentPassword())) {
            throw new InvalidRequestException("New password must be different from the current password");
        }
        user.changePasswordHash(passwordEncoder.encode(body.getNewPassword()));
        auditService.record(user.getId(), AuditAction.PASSWORD_CHANGED, "USER", user.getId(), null);

        // Keep THIS session valid: replace the stored principal with one carrying the new fingerprint.
        AuthenticatedUser refreshed = AuthenticatedUser.from(user);
        refreshed.eraseCredentials();
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                refreshed, null, refreshed.getAuthorities()));
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private void recordFailedLogin(String login, Exception ex) {
        // Link the attempt to an account if one matches, but never store the typed login text or password.
        UUID knownUserId = userDetailsService.findByLogin(login).map(User::getId).orElse(null);
        String reason = (ex instanceof DisabledException) ? "ACCOUNT_DISABLED" : "BAD_CREDENTIALS";
        auditService.record(knownUserId, AuditAction.LOGIN_FAILED, "USER", knownUserId, Map.of("reason", reason));
    }
}
