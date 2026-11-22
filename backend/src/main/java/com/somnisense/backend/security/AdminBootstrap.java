package com.somnisense.backend.security;

import com.somnisense.backend.audit.service.AuditAction;
import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.user.UserRules;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Creates the very first ADMIN account, because there is no public signup (decision 2026-11-21).
 *
 * Runs once at every startup (ApplicationRunner), but only acts when the users table is EMPTY.
 * The account details come from environment variables (or the git-ignored .env file):
 *   SOMNISENSE_ADMIN_USERNAME, SOMNISENSE_ADMIN_EMAIL, SOMNISENSE_ADMIN_PASSWORD
 * Nothing is hard-coded and the password is never logged.
 *
 * @Value("${NAME:}") reads a setting; the part after ':' is the default (here: empty).
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private static final Pattern USERNAME_PATTERN = Pattern.compile(UserRules.USERNAME_REGEX);
    private static final int MIN_PASSWORD_LENGTH = UserRules.PASSWORD_MIN_LENGTH;
    private static final int MAX_PASSWORD_LENGTH = UserRules.PASSWORD_MAX_LENGTH;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final String username;
    private final String email;
    private final String password;
    private final boolean enabled;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder, AuditService auditService,
                          @Value("${SOMNISENSE_ADMIN_USERNAME:}") String username,
                          @Value("${SOMNISENSE_ADMIN_EMAIL:}") String email,
                          @Value("${SOMNISENSE_ADMIN_PASSWORD:}") String password,
                          @Value("${somnisense.bootstrap-admin.enabled:true}") boolean enabled) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.username = username;
        this.email = email;
        this.password = password;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || userRepository.count() > 0) {
            return;   // accounts already exist - never touch them
        }
        if (username.isBlank() || email.isBlank() || password.isBlank()) {
            log.warn("No staff accounts exist and SOMNISENSE_ADMIN_USERNAME/EMAIL/PASSWORD are not all set; "
                    + "nobody can log in until they are configured (see .env.example).");
            return;
        }
        String normalizedUsername = User.normalizeUsername(username);
        String normalizedEmail = User.normalizeEmail(email);
        if (!USERNAME_PATTERN.matcher(normalizedUsername).matches()) {
            log.error("SOMNISENSE_ADMIN_USERNAME must be 3-50 chars of a-z, 0-9, '.', '_' or '-'; admin not created.");
            return;
        }
        if (!normalizedEmail.contains("@") || normalizedEmail.length() > 255) {
            log.error("SOMNISENSE_ADMIN_EMAIL is not a valid email; admin not created.");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            log.error("SOMNISENSE_ADMIN_PASSWORD must be {}-{} characters; admin not created.",
                    MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH);
            return;
        }

        User admin = new User(normalizedUsername, normalizedEmail, passwordEncoder.encode(password), Role.ADMIN);
        userRepository.save(admin);
        auditService.record(admin.getId(), AuditAction.ADMIN_BOOTSTRAPPED, "USER", admin.getId(),
                Map.of("role", Role.ADMIN.name()));
        log.info("Created initial ADMIN account '{}'.", normalizedUsername);
    }
}
