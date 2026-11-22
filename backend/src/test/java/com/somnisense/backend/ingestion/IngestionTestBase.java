package com.somnisense.backend.ingestion;

import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.support.TestDatabase;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Shared fixture for Stage 9 tests: an admin, an operator, a simulated patient + device + assignment + ACTIVE session. */
abstract class IngestionTestBase {

    static final String KEY = "test-ingestion-key-0123456789abcdef-0123456789";   // from application-test.properties
    static final String PW = "a-test-password-123";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository userRepository;
    @Autowired
    PatientRepository patientRepository;
    @Autowired
    DeviceRepository deviceRepository;
    @Autowired
    DeviceAssignmentRepository assignmentRepository;
    @Autowired
    MonitoringSessionRepository sessionRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JdbcTemplate jdbcTemplate;

    User admin;
    Patient patient;
    Device device;
    DeviceAssignment assignment;
    MonitoringSession session;
    Instant sessionStart;

    @BeforeEach
    void createFixture() {
        TestDatabase.clean(jdbcTemplate);
        admin = userRepository.save(new User("admin", "admin@test.local", passwordEncoder.encode(PW), Role.ADMIN));
        userRepository.save(new User("operator1", "op1@test.local", passwordEncoder.encode(PW), Role.OPERATOR));
        UUID by = admin.getId();
        patient = patientRepository.save(new Patient("PT-ING00001", "Ingest One", null, null, null, SourceType.SIMULATED, by));
        device = deviceRepository.save(new Device("DEV-ING1", SourceType.SIMULATED, null, null, by));
        assignment = assignmentRepository.save(new DeviceAssignment(device.getId(), patient.getId(), by));
        sessionStart = Instant.now().minusSeconds(600);
        session = sessionRepository.save(new MonitoringSession("SES-TEST-ING1", patient.getId(), assignment.getId(), by,
                sessionStart));
    }

    /** One reading as JSON. Pass null to omit a value (JSON null). */
    static String reading(String key, Instant measuredAt, String spo2, String heartRate) {
        return "{\"readingKey\":" + (key == null ? "null" : "\"" + key + "\"")
                + ",\"measuredAt\":" + (measuredAt == null ? "null" : "\"" + measuredAt + "\"")
                + ",\"spo2\":" + spo2 + ",\"heartRate\":" + heartRate + "}";
    }

    static String batch(String deviceCode, String... readings) {
        return "{\"deviceCode\":\"" + deviceCode + "\",\"readings\":[" + String.join(",", readings) + "]}";
    }

    MvcResult ingest(String key, String body) throws Exception {
        var request = post("/api/v1/ingestion/readings").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("X-Ingestion-Key", key);
        }
        return mockMvc.perform(request).andReturn();
    }

    int storedReadings() {
        return jdbcTemplate.queryForObject("select count(*) from sensor_readings", Integer.class);
    }
}
