package com.somnisense.backend.user.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Response of POST /api/v1/users (201):
 * {"user":{...}, "temporaryPassword":"Kp7mQ2xRt9WvB4nE"}
 *
 * temporaryPassword appears in THIS response only. It is never stored in plain text and cannot be
 * retrieved again; if it is lost, an Admin uses reset-password to issue a new one.
 */
@JsonPropertyOrder({"user", "temporaryPassword"})
public class CreatedUserResponse {

    private final UserResponse user;
    private final String temporaryPassword;

    public CreatedUserResponse(UserResponse user, String temporaryPassword) {
        this.user = user;
        this.temporaryPassword = temporaryPassword;
    }

    public UserResponse getUser() {
        return user;
    }

    public String getTemporaryPassword() {
        return temporaryPassword;
    }
}
