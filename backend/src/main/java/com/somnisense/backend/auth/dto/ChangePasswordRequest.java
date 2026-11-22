package com.somnisense.backend.auth.dto;

import com.somnisense.backend.user.UserRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/v1/auth/password: {"currentPassword":"...","newPassword":"..."} */
public class ChangePasswordRequest {

    @NotBlank
    @Size(max = UserRules.PASSWORD_MAX_LENGTH)
    private String currentPassword;

    @NotBlank
    @Size(min = UserRules.PASSWORD_MIN_LENGTH, max = UserRules.PASSWORD_MAX_LENGTH)
    private String newPassword;

    public ChangePasswordRequest() {
    }

    public ChangePasswordRequest(String currentPassword, String newPassword) {
        this.currentPassword = currentPassword;
        this.newPassword = newPassword;
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
