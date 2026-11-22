package com.somnisense.backend.security;

import com.somnisense.backend.audit.service.AuditAction;
import com.somnisense.backend.audit.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/** Records a LOGOUT audit entry. Spring Security calls this during POST /api/v1/auth/logout. */
@Component
public class AuditLogoutHandler implements LogoutHandler {

    private final AuditService auditService;

    public AuditLogoutHandler(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            auditService.record(user.getId(), AuditAction.LOGOUT, "USER", user.getId(), null);
        }
    }
}
