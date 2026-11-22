package com.somnisense.backend.auth.controller;

import com.somnisense.backend.auth.dto.ChangePasswordRequest;
import com.somnisense.backend.auth.dto.CsrfTokenResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.somnisense.backend.auth.dto.CurrentUserResponse;
import com.somnisense.backend.auth.dto.LoginRequest;
import com.somnisense.backend.auth.service.AuthService;
import com.somnisense.backend.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints. Logout is handled by Spring Security itself (see SecurityConfig):
 * POST /api/v1/auth/logout -> 204.
 *
 * - @RestController: every method's return value is written as JSON.
 * - @RequestMapping: common URL prefix for all methods in this class.
 * - @AuthenticationPrincipal: Spring passes in the logged-in user taken from the session.
 *
 * The controller stays thin: it only handles HTTP details and delegates the logic to AuthService.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Public. Ensures the XSRF-TOKEN cookie exists and also returns the token for tools like Postman. */
    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken csrfToken) {
        return new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    /** Public (but needs the CSRF header). 200 + current user, or 401 INVALID_CREDENTIALS. */
    @PostMapping("/login")
    public CurrentUserResponse login(@Valid @RequestBody LoginRequest loginRequest,
                                     HttpServletRequest request, HttpServletResponse response) {
        return authService.login(loginRequest, request, response);
    }

    /** Any logged-in staff member changes their OWN password. 204 on success; other sessions of the user end. */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AuthenticatedUser principal,
                               @Valid @RequestBody ChangePasswordRequest body,
                               HttpServletRequest request, HttpServletResponse response) {
        authService.changeOwnPassword(principal, body, request, response);
    }

    /** Any logged-in staff member. The frontend calls this on start-up to learn who is logged in and their role. */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.currentUser(principal);
    }
}
