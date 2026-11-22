package com.somnisense.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Defines HOW passwords are hashed.
 *
 * - @Configuration: a class that declares beans.
 * - @Bean: the object returned by the method becomes a shared Spring bean that any class can receive
 *   through its constructor.
 *
 * BCrypt is a slow, salted one-way hash: the database only ever stores the hash (e.g. "$2a$10$..."),
 * and login works by hashing the typed password and comparing. Plain-text passwords are never stored.
 * Note: BCrypt only uses the first 72 bytes of a password, so we cap passwords at 72 characters elsewhere.
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
