package com.somnisense.backend.assignment;

import com.somnisense.backend.assignment.service.AssignmentService;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two staff members assign at the very same moment. Exactly one must win; the other gets a clear 409 code
 * (never a 500, never two active assignments). Repeated to vary the timing.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentAssignmentTest {

    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID actor;
    private Patient p1;
    private Patient p2;
    private Device d1;
    private Device d2;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
        actor = userRepository.save(new User("operator1", "op1@test.local", "$2a$10$unused", Role.OPERATOR)).getId();
        p1 = patientRepository.save(new Patient("PT-CONC0001", "C1", null, null, null, SourceType.SIMULATED, actor));
        p2 = patientRepository.save(new Patient("PT-CONC0002", "C2", null, null, null, SourceType.SIMULATED, actor));
        d1 = deviceRepository.save(new Device("DEV-CONC1", SourceType.SIMULATED, null, null, actor));
        d2 = deviceRepository.save(new Device("DEV-CONC2", SourceType.SIMULATED, null, null, actor));
    }

    @RepeatedTest(5)
    void sameDeviceToTwoPatientsAtOnce() throws Exception {
        List<ErrorCode> outcome = race(
                () -> assignmentService.assign(d1.getId(), p1.getId(), false, actor),
                () -> assignmentService.assign(d1.getId(), p2.getId(), false, actor));
        assertThat(outcome).containsExactlyInAnyOrder(null, ErrorCode.DEVICE_ALREADY_ASSIGNED);
        assertActiveRows(1);
    }

    @RepeatedTest(5)
    void twoDevicesToSamePatientAtOnce() throws Exception {
        List<ErrorCode> outcome = race(
                () -> assignmentService.assign(d1.getId(), p1.getId(), false, actor),
                () -> assignmentService.assign(d2.getId(), p1.getId(), false, actor));
        assertThat(outcome).containsExactlyInAnyOrder(null, ErrorCode.PATIENT_ALREADY_HAS_DEVICE);
        assertActiveRows(1);
    }

    private void assertActiveRows(int expected) {
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from device_assignments where released_at is null", Integer.class)).isEqualTo(expected);
    }

    /** Runs both actions at the same instant; returns null for success or the ErrorCode of the failure. */
    private List<ErrorCode> race(Callable<?> a, Callable<?> b) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ErrorCode> fa = pool.submit(() -> attempt(start, a));
            Future<ErrorCode> fb = pool.submit(() -> attempt(start, b));
            start.countDown();
            return java.util.Arrays.asList(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static ErrorCode attempt(CountDownLatch start, Callable<?> action) throws Exception {
        start.await();
        try {
            action.call();
            return null;
        } catch (ApiException e) {
            return e.getErrorCode();
        }
    }
}
