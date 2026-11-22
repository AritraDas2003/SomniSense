package com.somnisense.backend.session;

import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.assignment.service.AssignmentService;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.session.service.SessionService;
import com.somnisense.backend.support.TestDatabase;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Races around sessions. Repeated to vary the timing. */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentSessionTest {

    @Autowired
    private SessionService sessionService;
    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private DeviceAssignmentRepository assignmentRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID actor;
    private Patient patient;
    private DeviceAssignment assignment;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        actor = userRepository.save(new User("operator1", "op1@test.local", "$2a$10$unused", Role.OPERATOR)).getId();
        patient = patientRepository.save(new Patient("PT-RACE0001", "R", null, null, null, SourceType.SIMULATED, actor));
        Device device = deviceRepository.save(new Device("DEV-RACE1", SourceType.SIMULATED, null, null, actor));
        assignment = assignmentRepository.save(new DeviceAssignment(device.getId(), patient.getId(), actor));
    }

    /** Two "Start" clicks at once: one creates the session, the other gets the same session back. */
    @RepeatedTest(5)
    void doubleStartCreatesOneSession() throws Exception {
        List<Object> results = race(
                () -> sessionService.start(patient.getId(), null, actor),
                () -> sessionService.start(patient.getId(), null, actor));
        SessionService.StartResult a = (SessionService.StartResult) results.get(0);
        SessionService.StartResult b = (SessionService.StartResult) results.get(1);
        assertThat(List.of(a.isCreated(), b.isCreated())).containsExactlyInAnyOrder(true, false);
        assertThat(a.getSession().getId()).isEqualTo(b.getSession().getId());
        assertThat(count("select count(*) from monitoring_sessions where status='ACTIVE'")).isEqualTo(1);
    }

    /**
     * "Start" and "Release" at once. Either the session wins (release gets SESSION_ACTIVE) or the release wins
     * (start gets NO_ACTIVE_ASSIGNMENT). It must NEVER end with an ACTIVE session on a released assignment.
     */
    @RepeatedTest(5)
    void startAndReleaseNeverLeaveAnOrphanedSession() throws Exception {
        List<Object> results = race(
                () -> sessionService.start(patient.getId(), null, actor),
                () -> assignmentService.release(assignment.getId(), actor));
        Object startResult = results.get(0);
        Object releaseResult = results.get(1);

        boolean sessionWon = startResult instanceof SessionService.StartResult;
        if (sessionWon) {
            assertThat(releaseResult).isEqualTo(ErrorCode.SESSION_ACTIVE);
        } else {
            assertThat(startResult).isEqualTo(ErrorCode.NO_ACTIVE_ASSIGNMENT);
            assertThat(releaseResult).isNotInstanceOf(ErrorCode.class);
        }
        assertThat(count("select count(*) from monitoring_sessions s join device_assignments a "
                + "on a.id = s.device_assignment_id where s.status = 'ACTIVE' and a.released_at is not null")).isZero();
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    /** Runs both at the same instant; each result is the return value, or the ErrorCode if it failed. */
    private List<Object> race(Callable<?> a, Callable<?> b) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> fa = pool.submit(() -> attempt(start, a));
            Future<Object> fb = pool.submit(() -> attempt(start, b));
            start.countDown();
            return Arrays.asList(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object attempt(CountDownLatch start, Callable<?> action) throws Exception {
        start.await();
        try {
            return action.call();
        } catch (ApiException e) {
            return e.getErrorCode();
        }
    }
}
