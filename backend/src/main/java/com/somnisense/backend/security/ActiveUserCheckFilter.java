package com.somnisense.backend.security;

import com.somnisense.backend.audit.service.AuditAction;
import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.common.web.ApiErrorWriter;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * "Deactivated users must not continue using protected APIs" (master prompt 6.1).
 *
 * The session holds a SNAPSHOT of the user taken at login. On every request by a logged-in user this filter
 * re-reads the account (one primary-key lookup) and ends the session immediately if the account was
 * deactivated, deleted, had its role changed, or had its password changed/reset since login. The client gets 401 SESSION_INVALID and must
 * log in again (and will then get the new role).
 *
 * Not a @Component - it is added to the security filter chain in SecurityConfig only.
 */
public class ActiveUserCheckFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final ApiErrorWriter apiErrorWriter;
    private final AuditService auditService;

    public ActiveUserCheckFilter(UserRepository userRepository, ApiErrorWriter apiErrorWriter,
                                 AuditService auditService) {
        this.userRepository = userRepository;
        this.apiErrorWriter = apiErrorWriter;
        this.auditService = auditService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser principal) {
            Optional<User> current = userRepository.findById(principal.getId());
            String reason = null;
            if (current.isEmpty()) {
                reason = "ACCOUNT_MISSING";
            } else if (!current.get().isActive()) {
                reason = "ACCOUNT_DEACTIVATED";
            } else if (current.get().getRole() != principal.getRole()) {
                reason = "ROLE_CHANGED";
            } else if (!AuthenticatedUser.fingerprint(current.get().getPasswordHash())
                    .equals(principal.getCredentialsFingerprint())) {
                reason = "PASSWORD_CHANGED";
            }
            if (reason != null) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
                auditService.record(current.map(User::getId).orElse(null), AuditAction.SESSION_REVOKED,
                        "USER", principal.getId(), Map.of("reason", reason));
                apiErrorWriter.write(request, response, ErrorCode.SESSION_INVALID);
                return;   // stop here: the request never reaches a controller
            }
        }
        filterChain.doFilter(request, response);
    }
}
