package com.somnisense.backend.user.dto;

import com.somnisense.backend.user.entity.User;

import java.time.Instant;
import java.util.UUID;

/** A staff account as shown in the Admin's staff table and detail view. Never contains the password hash. */
public class UserResponse {

    private final UUID id;
    private final String username;
    private final String email;
    private final String role;
    private final boolean active;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant lastLoginAt;

    public UserResponse(UUID id, String username, String email, String role, boolean active,
                        Instant createdAt, Instant updatedAt, Instant lastLoginAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.role = role;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastLoginAt = lastLoginAt;
    }

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole().name(),
                user.isActive(), user.getCreatedAt(), user.getUpdatedAt(), user.getLastLoginAt());
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
