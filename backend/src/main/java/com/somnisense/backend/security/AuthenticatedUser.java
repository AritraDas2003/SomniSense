package com.somnisense.backend.security;

import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The logged-in staff member, as Spring Security sees them ("the principal").
 *
 * After login this object is stored inside the HTTP session, so it must be small and serializable
 * (UserDetails extends Serializable). It is a SNAPSHOT taken at login; ActiveUserCheckFilter re-checks
 * the database on every request in case the account was deactivated or its role changed.
 *
 * Spring Security checks roles as "authorities" named "ROLE_<role>", e.g. ROLE_ADMIN.
 * hasRole("ADMIN") in SecurityConfig matches that automatically.
 */
public class AuthenticatedUser implements UserDetails, CredentialsContainer {

    private final UUID id;
    private final String username;
    private final Role role;
    private final boolean active;
    /**
     * A short SHA-256 digest of the password HASH at login time. It is not secret and cannot be reversed;
     * ActiveUserCheckFilter compares it with the current hash, so a password change or Admin reset ends every
     * OTHER session of that user.
     */
    private final String credentialsFingerprint;
    private String passwordHash;   // not final: erased right after a successful login

    public AuthenticatedUser(UUID id, String username, String passwordHash, Role role, boolean active) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = active;
        this.credentialsFingerprint = fingerprint(passwordHash);
    }

    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getUsername(), user.getPasswordHash(),
                user.getRole(), user.isActive());
    }

    /** First 16 hex characters of SHA-256(passwordHash). */
    public static String fingerprint(String passwordHash) {
        if (passwordHash == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(passwordHash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);   // every Java runtime has it
        }
    }

    public UUID getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public String getCredentialsFingerprint() {
        return credentialsFingerprint;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    /** Spring Security refuses login for disabled accounts (is_active = false). */
    @Override
    public boolean isEnabled() {
        return active;
    }

    /** Called by Spring Security after login so the hash is not kept in the session. */
    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
