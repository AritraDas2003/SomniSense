package com.somnisense.backend.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates one-time passwords for new accounts and admin resets (decision 2026-11-21).
 *
 * - SecureRandom: cryptographically strong randomness (java.util.Random is predictable - never use it for secrets).
 * - 16 characters from a 54-character alphabet = about 92 bits of randomness.
 * - Look-alike characters (0/O, 1/l/I) are left out so the password can be read out or typed reliably.
 * The password is returned to the Admin exactly once and only its BCrypt hash is stored.
 */
@Component
public class TemporaryPasswordGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int LENGTH = 16;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
