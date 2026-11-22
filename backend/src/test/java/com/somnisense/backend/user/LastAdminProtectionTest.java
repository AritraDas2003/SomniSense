package com.somnisense.backend.user;

import com.somnisense.backend.support.TestDatabase;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import com.somnisense.backend.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Never deactivate or demote the last active Admin."
 *
 * Through the HTTP API this cannot happen one request at a time: an Admin may not act on their own account,
 * and if another active Admin is the target there are at least two. The dangerous case is TWO Admins acting
 * at the SAME moment (each deactivating the other). These tests call UserService directly to cover both.
 */
@SpringBootTest
@ActiveProfiles("test")
class LastAdminProtectionTest {

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User adminA;
    private User adminB;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        adminA = userRepository.save(new User("admin-a", "a@test.local", "$2a$10$notusedinthistest", Role.ADMIN));
        adminB = userRepository.save(new User("admin-b", "b@test.local", "$2a$10$notusedinthistest", Role.ADMIN));
    }

    @Test
    void cannotDeactivateOrDemoteTheOnlyActiveAdmin() {
        UUID someActor = adminB.getId();
        userService.deactivate(adminB.getId(), adminA.getId());   // now A is the only active Admin

        assertThatThrownBy(() -> userService.deactivate(adminA.getId(), someActor))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.LAST_ACTIVE_ADMIN);
        assertThatThrownBy(() -> userService.changeRole(adminA.getId(), Role.OPERATOR, someActor))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.LAST_ACTIVE_ADMIN);

        assertThat(userRepository.countByRoleAndActiveTrue(Role.ADMIN)).isEqualTo(1);
    }

    /**
     * Two Admins deactivate each other at the same instant. Without the row lock both would see "2 active
     * Admins" and both succeed, leaving zero. With the lock, exactly one succeeds. Repeated to vary the timing.
     */
    @RepeatedTest(5)
    void concurrentMutualDeactivationLeavesOneAdmin() throws Exception {
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            results.add(pool.submit(() -> attempt(startTogether, adminB.getId(), adminA.getId())));
            results.add(pool.submit(() -> attempt(startTogether, adminA.getId(), adminB.getId())));
            startTogether.countDown();

            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(userRepository.countByRoleAndActiveTrue(Role.ADMIN)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** true = deactivation succeeded, false = refused as LAST_ACTIVE_ADMIN. Any other error fails the test. */
    private boolean attempt(CountDownLatch start, UUID target, UUID actor) throws InterruptedException {
        start.await();
        try {
            userService.deactivate(target, actor);
            return true;
        } catch (ApiException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LAST_ACTIVE_ADMIN);
            return false;
        }
    }
}
