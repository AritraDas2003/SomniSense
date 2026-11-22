package com.somnisense.backend.security;

import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Tells Spring Security how to find a staff account during login.
 *
 * The login field accepts a username OR an email (decision 2026-11-21):
 * - contains "@" -> look up by email (usernames are not allowed to contain "@")
 * - otherwise    -> look up by username
 * Both are compared after trimming and lower-casing.
 *
 * Because this class is a UserDetailsService bean, Spring Boot no longer creates its default
 * in-memory "user" with a generated password.
 */
@Service
public class StaffUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public StaffUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String login) throws UsernameNotFoundException {
        return findByLogin(login)
                .map(AuthenticatedUser::from)
                // The message is never shown to the client (Spring turns it into "bad credentials").
                .orElseThrow(() -> new UsernameNotFoundException("No account for the given login"));
    }

    @Transactional(readOnly = true)
    public Optional<User> findByLogin(String login) {
        if (login == null || login.isBlank()) {
            return Optional.empty();
        }
        return login.contains("@")
                ? userRepository.findByEmail(User.normalizeEmail(login))
                : userRepository.findByUsername(User.normalizeUsername(login));
    }
}
