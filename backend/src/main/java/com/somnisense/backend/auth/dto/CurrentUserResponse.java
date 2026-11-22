package com.somnisense.backend.auth.dto;

import com.somnisense.backend.user.entity.User;

import java.time.Instant;
import java.util.UUID;

/**
 * The logged-in staff member as returned by login and GET /api/v1/auth/me.
 * Deliberately has NO password hash field: a DTO only contains what the frontend needs.
 */
public class CurrentUserResponse {

    private final UUID id;
    private final String username;
    private final String email;
    private final String role;
    private final boolean active;
    private final Instant lastLoginAt;
    private final Instant createdAt;

    public CurrentUserResponse(UUID id, String username, String email, String role, boolean active,
                               Instant lastLoginAt, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.role = role;
        this.active = active;
        this.lastLoginAt = lastLoginAt;
        this.createdAt = createdAt;
    }

    public static CurrentUserResponse from(User user) {
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole().name(),
                user.isActive(), user.getLastLoginAt(), user.getCreatedAt());
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
