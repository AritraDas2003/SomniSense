package com.somnisense.backend.user;

/**
 * Account rules shared by the admin bootstrap, staff management and password changes,
 * so they are defined once.
 */
public final class UserRules {

    /** 3-50 chars: letters, digits, dot, underscore, hyphen. No "@" (login treats "@" as an email). Stored lower-case. */
    public static final String USERNAME_REGEX = "^[A-Za-z0-9._-]{3,50}$";

    public static final int PASSWORD_MIN_LENGTH = 12;
    /** BCrypt only uses the first 72 bytes of a password. */
    public static final int PASSWORD_MAX_LENGTH = 72;

    private UserRules() {
    }
}
