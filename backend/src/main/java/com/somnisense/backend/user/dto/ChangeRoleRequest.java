package com.somnisense.backend.user.dto;

import com.somnisense.backend.user.entity.Role;
import jakarta.validation.constraints.NotNull;

/** Body of PATCH /api/v1/users/{id}/role: {"role":"ADMIN"} */
public class ChangeRoleRequest {

    @NotNull
    private Role role;

    public ChangeRoleRequest() {
    }

    public ChangeRoleRequest(Role role) {
        this.role = role;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }
}
