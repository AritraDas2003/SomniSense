package com.somnisense.backend.user.dto;

/** Response of POST /api/v1/users/{id}/reset-password: {"temporaryPassword":"..."} - shown once only. */
public class TemporaryPasswordResponse {

    private final String temporaryPassword;

    public TemporaryPasswordResponse(String temporaryPassword) {
        this.temporaryPassword = temporaryPassword;
    }

    public String getTemporaryPassword() {
        return temporaryPassword;
    }
}
