package com.somnisense.backend.user.dto;

import com.somnisense.backend.user.UserRules;
import com.somnisense.backend.user.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/v1/users: {"username":"nurse.kim","email":"kim@clinic.local","role":"OPERATOR"}
 * There is no password field: the server generates a temporary password (decision 2026-11-21).
 */
public class CreateUserRequest {

    @NotBlank
    @Pattern(regexp = UserRules.USERNAME_REGEX,
            message = "must be 3-50 characters: letters, digits, '.', '_' or '-'")
    private String username;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @NotNull
    private Role role;

    public CreateUserRequest() {
    }

    public CreateUserRequest(String username, String email, Role role) {
        this.username = username;
        this.email = email;
        this.role = role;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }
}
