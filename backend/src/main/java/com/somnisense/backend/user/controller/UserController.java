package com.somnisense.backend.user.controller;

import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.security.AuthenticatedUser;
import com.somnisense.backend.user.dto.ChangeRoleRequest;
import com.somnisense.backend.user.dto.CreateUserRequest;
import com.somnisense.backend.user.dto.CreatedUserResponse;
import com.somnisense.backend.user.dto.TemporaryPasswordResponse;
import com.somnisense.backend.user.dto.UserResponse;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Admin-only staff management (Stage 4). See docs/api/USERS.md.
 *
 * Access is checked twice ("defence in depth"):
 * 1. SecurityConfig: /api/v1/users/** requires ROLE_ADMIN.
 * 2. @PreAuthorize on this class: even if the URL rule were changed by mistake, these methods still refuse
 *    non-Admins.
 *
 * The acting Admin always comes from the session (@AuthenticationPrincipal), never from the request body.
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** GET /api/v1/users?role=OPERATOR&active=true&q=kim&page=0&size=20&sort=createdAt&direction=desc */
    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(required = false) Role role,
                                           @RequestParam(required = false) Boolean active,
                                           @RequestParam(required = false) @Size(max = 100) String q,
                                           @RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                           @RequestParam(defaultValue = "createdAt") String sort,
                                           @RequestParam(defaultValue = "desc") String direction) {
        return userService.list(role, active, q, page, size, sort, direction);
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return userService.get(id);
    }

    /** 201 Created + Location header + the one-time temporary password. */
    @PostMapping
    public ResponseEntity<CreatedUserResponse> create(@Valid @RequestBody CreateUserRequest request,
                                                      @AuthenticationPrincipal AuthenticatedUser actor) {
        CreatedUserResponse created = userService.create(request, actor.getId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.getUser().getId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PatchMapping("/{id}/role")
    public UserResponse changeRole(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest request,
                                   @AuthenticationPrincipal AuthenticatedUser actor) {
        return userService.changeRole(id, request.getRole(), actor.getId());
    }

    @PostMapping("/{id}/deactivate")
    public UserResponse deactivate(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        return userService.deactivate(id, actor.getId());
    }

    @PostMapping("/{id}/reactivate")
    public UserResponse reactivate(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        return userService.reactivate(id, actor.getId());
    }

    @PostMapping("/{id}/reset-password")
    public TemporaryPasswordResponse resetPassword(@PathVariable UUID id,
                                                   @AuthenticationPrincipal AuthenticatedUser actor) {
        return userService.resetPassword(id, actor.getId());
    }
}
