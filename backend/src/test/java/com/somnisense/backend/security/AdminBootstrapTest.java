package com.somnisense.backend.security;

import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests (no Spring, no database): the repository, encoder and audit service are Mockito "mocks" -
 * fake objects whose answers we script and whose calls we can verify.
 */
class AdminBootstrapTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        auditService = mock(AuditService.class);
        when(passwordEncoder.encode(any())).thenReturn("$2a$10$hashed");
    }

    private AdminBootstrap bootstrap(String username, String email, String password, boolean enabled) {
        return new AdminBootstrap(userRepository, passwordEncoder, auditService, username, email, password, enabled);
    }

    @Test
    void createsAdminWhenTableIsEmptyAndSettingsAreValid() {
        when(userRepository.count()).thenReturn(0L);

        bootstrap("  Admin ", "Admin@Example.Local", "a-long-enough-password", true).run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("admin");
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@example.local");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("$2a$10$hashed");
        assertThat(saved.getValue().isActive()).isTrue();
    }

    @Test
    void doesNothingWhenUsersAlreadyExist() {
        when(userRepository.count()).thenReturn(3L);
        bootstrap("admin", "admin@example.local", "a-long-enough-password", true).run(null);
        verify(userRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenSettingsAreMissing() {
        when(userRepository.count()).thenReturn(0L);
        bootstrap("", "", "", true).run(null);
        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsShortPassword() {
        when(userRepository.count()).thenReturn(0L);
        bootstrap("admin", "admin@example.local", "short", true).run(null);
        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsUsernameContainingAtSign() {
        when(userRepository.count()).thenReturn(0L);
        bootstrap("admin@x", "admin@example.local", "a-long-enough-password", true).run(null);
        verify(userRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenDisabled() {
        when(userRepository.count()).thenReturn(0L);
        bootstrap("admin", "admin@example.local", "a-long-enough-password", false).run(null);
        verify(userRepository, never()).save(any());
    }
}
