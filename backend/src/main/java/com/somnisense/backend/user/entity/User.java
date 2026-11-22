package com.somnisense.backend.user.entity;

import com.somnisense.backend.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A staff account. Maps 1:1 to the approved "users" table.
 *
 * Annotations:
 * - @Entity: Hibernate manages objects of this class as database rows.
 * - @Table(name = "users"): the exact table name (never rely on naming guesses).
 * - @Column(name = ...): the exact column name for each field; nullable/length document the SQL and are
 *   checked by our tests, but Hibernate never changes the table (ddl-auto=validate).
 *
 * This is NOT sent to the browser. Controllers return DTOs, so passwordHash can never leak.
 * There are no public setters: changes go through named methods (deactivate(), changeRole(), ...)
 * so every state change is deliberate and easy to find.
 */
@Entity
@Table(name = "users")
@DynamicUpdate   // UPDATE only the columns that changed, so two concurrent edits of different fields don't overwrite each other
public class User extends AssignedIdEntity {

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** For JPA only. */
    protected User() {
    }

    /** Creates a new, active account. The password must already be BCrypt-hashed. */
    public User(String username, String email, String passwordHash, Role role) {
        super(UUID.randomUUID());
        this.username = normalizeUsername(username);
        this.email = normalizeEmail(email);
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.role = Objects.requireNonNull(role, "role");
        this.active = true;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Usernames are stored trimmed and lower-case, so "Admin" and "admin" are the same account. */
    public static String normalizeUsername(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeEmail(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- state changes

    /** A login is not an account change, so it updates last_login_at only (not updated_at). */
    public void recordLogin(Instant when) {
        this.lastLoginAt = when;
    }

    // Each account change sets updated_at immediately, so the API response returned in the same
    // request already shows the new time. (A @PreUpdate callback would only run at commit - too late.)

    public void deactivate() {
        this.active = false;
        touch();
    }

    public void activate() {
        this.active = true;
        touch();
    }

    public void changeRole(Role newRole) {
        this.role = Objects.requireNonNull(newRole, "role");
        touch();
    }

    public void changePasswordHash(String newPasswordHash) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "passwordHash");
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // ---------------------------------------------------------------- getters

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
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
