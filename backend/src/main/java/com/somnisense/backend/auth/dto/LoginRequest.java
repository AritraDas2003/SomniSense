package com.somnisense.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JSON body for POST /api/v1/auth/login:  {"login": "admin", "password": "..."}
 *
 * "login" may be a username or an email. Jackson fills this object using the no-argument
 * constructor and the setters. The @NotBlank/@Size rules are checked because the controller
 * parameter is marked @Valid; failures become a 400 VALIDATION_FAILED response.
 */
public class LoginRequest {

    @NotBlank
    @Size(max = 255)
    private String login;

    @NotBlank
    @Size(max = 72)
    private String password;

    public LoginRequest() {
    }

    public LoginRequest(String login, String password) {
        this.login = login;
        this.password = password;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
