package com.somnisense.backend.session;

import com.jayway.jsonpath.JsonPath;
import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.support.LoggedInClient;
import com.somnisense.backend.support.TestDatabase;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 8: monitoring sessions through the real HTTP stack, against somnisense_test_db. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionTest {

    private static final String PW = "a-test-password-123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private DeviceAssignmentRepository assignmentRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User operatorUser;
    private LoggedInClient admin;
    private LoggedInClient op;
    private Patient patient;
    private Patient otherPatient;
    private Device device;
    private DeviceAssignment assignment;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(jdbcTemplate);
        User adminUser = userRepository.save(new User("admin", "admin@test.local", passwordEncoder.encode(PW), Role.ADMIN));
        operatorUser = userRepository.save(new User("operator1", "op1@test.local", passwordEncoder.encode(PW), Role.OPERATOR));
        admin = LoggedInClient.login(mockMvc, "admin", PW);
        op = LoggedInClient.login(mockMvc, "operator1", PW);
        UUID by = adminUser.getId();
        patient = patientRepository.save(new Patient("PT-SESS0001", "Sess One", null, null, null, SourceType.SIMULATED, by));
        otherPatient = patientRepository.save(new Patient("PT-SESS0002", "Sess Two", null, null, null, SourceType.SIMULATED, by));
        device = deviceRepository.save(new Device("DEV-SESS1", SourceType.SIMULATED, null, null, by));
        assignment = assignmentRepository.save(new DeviceAssignment(device.getId(), patient.getId(), by));
    }

    private MvcResult start(String json) throws Exception {
        return mockMvc.perform(op.with(post("/api/v1/sessions")).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
    }

    private MvcResult start(Patient p) throws Exception {
        return start("{\"patientId\":\"" + p.getId() + "\"}");
    }

    private String startOk() throws Exception {
        MvcResult r = start(patient);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ------------------------------------------------------------------ start

    @Test
    void startCreatesActiveSessionVisibleOnThePatient() throws Exception {
        String today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        // a client-supplied startedBy must be ignored
        MvcResult r = start("{\"patientId\":\"" + patient.getId() + "\",\"startedBy\":\"" + UUID.randomUUID() + "\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(r.getResponse().getHeader("Location")).contains("/api/v1/sessions/");
        String json = body(r);
        assertThat((String) JsonPath.read(json, "$.sessionCode")).matches("^SES-" + today + "-[2-9A-HJ-NP-Z]{6}$");
        assertThat(json).contains("\"status\":\"ACTIVE\"", "\"sourceType\":\"SIMULATED\"", "\"deviceCode\":\"DEV-SESS1\"",
                "\"patientCode\":\"PT-SESS0001\"", "\"startedBy\":\"" + operatorUser.getId() + "\"", "\"endedAt\":null",
                "\"deviceAssignmentId\":\"" + assignment.getId() + "\"");

        String id = JsonPath.read(json, "$.id");
        mockMvc.perform(op.with(get("/api/v1/patients/" + patient.getId())))
                .andExpect(jsonPath("$.activeSession.sessionId").value(id));
        mockMvc.perform(op.with(get("/api/v1/patients")).param("q", "PT-SESS0002"))
                .andExpect(jsonPath("$.content[0].activeSession").value(nullValue()));
    }

    @Test
    void startingTwiceReturnsTheRunningSession() throws Exception {
        String id = startOk();
        MvcResult again = start(patient);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(body(again), "$.id")).isEqualTo(id);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action='SESSION_STARTED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void staleAssignmentIdIsRejected() throws Exception {
        MvcResult wrong = start("{\"patientId\":\"" + patient.getId() + "\",\"deviceAssignmentId\":\"" + UUID.randomUUID() + "\"}");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(wrong)).contains("ASSIGNMENT_MISMATCH");
        MvcResult right = start("{\"patientId\":\"" + patient.getId() + "\",\"deviceAssignmentId\":\"" + assignment.getId() + "\"}");
        assertThat(right.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void startNeedsAnAssignedEnrolledDeviceAndAnActivePatient() throws Exception {
        assertThat(body(start(otherPatient))).contains("NO_ACTIVE_ASSIGNMENT");

        mockMvc.perform(admin.with(patch("/api/v1/patients/" + otherPatient.getId() + "/status"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}")).andExpect(status().isOk());
        assertThat(body(start(otherPatient))).contains("PATIENT_NOT_ACTIVE");

        mockMvc.perform(op.with(patch("/api/v1/devices/" + device.getId() + "/enrollment"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enrollmentStatus\":\"SETUP_REQUIRED\"}"));
        MvcResult setup = start(patient);
        assertThat(setup.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(setup)).contains("DEVICE_NOT_AVAILABLE", "SETUP_REQUIRED");

        assertThat(start("{\"patientId\":\"" + UUID.randomUUID() + "\"}").getResponse().getStatus()).isEqualTo(404);
        assertThat(start("{}").getResponse().getStatus()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ end / cancel

    @Test
    void endIsFinalAndRepeatingItIsSafe() throws Exception {
        String id = startOk();
        mockMvc.perform(op.with(post("/api/v1/sessions/" + id + "/end")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endedBy").value(operatorUser.getId().toString()))
                .andExpect(jsonPath("$.endedAt").exists())
                .andExpect(jsonPath("$.durationSeconds").isNumber());
        mockMvc.perform(op.with(post("/api/v1/sessions/" + id + "/end"))).andExpect(status().isOk());
        mockMvc.perform(op.with(post("/api/v1/sessions/" + id + "/cancel")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_TRANSITION"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action='SESSION_ENDED'", Integer.class)).isEqualTo(1);
        mockMvc.perform(op.with(get("/api/v1/patients/" + patient.getId())))
                .andExpect(jsonPath("$.activeSession").value(nullValue()));
    }

    @Test
    void cancelIsFinalToo() throws Exception {
        String id = startOk();
        mockMvc.perform(op.with(post("/api/v1/sessions/" + id + "/cancel")))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(op.with(post("/api/v1/sessions/" + id + "/end")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("CANCELLED")));
        mockMvc.perform(op.with(post("/api/v1/sessions/" + UUID.randomUUID() + "/end"))).andExpect(status().isNotFound());
    }

    @Test
    void aNewSessionCanStartAfterTheOldOneEndsAndHistoryIsListed() throws Exception {
        String first = startOk();
        mockMvc.perform(op.with(post("/api/v1/sessions/" + first + "/end")));
        String second = startOk();
        assertThat(second).isNotEqualTo(first);

        mockMvc.perform(op.with(get("/api/v1/sessions")).param("patientId", patient.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second));   // newest first
        mockMvc.perform(op.with(get("/api/v1/sessions")).param("status", "ENDED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(first));
        mockMvc.perform(op.with(get("/api/v1/sessions"))
                        .param("startedFrom", "2030-01-01T00:00:00Z").param("startedTo", "2020-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(op.with(get("/api/v1/sessions")).param("startedFrom", "2999-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------ interaction with assignments

    @Test
    void releaseAndReassignAreBlockedWhileASessionIsActive() throws Exception {
        String sessionId = startOk();
        String code = JsonPath.read(mockMvc.perform(op.with(get("/api/v1/sessions/" + sessionId)))
                .andReturn().getResponse().getContentAsString(), "$.sessionCode");

        mockMvc.perform(op.with(post("/api/v1/device-assignments/" + assignment.getId() + "/release")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_ACTIVE"))
                .andExpect(jsonPath("$.message").value(containsString(code)));

        mockMvc.perform(op.with(post("/api/v1/device-assignments")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + device.getId() + "\",\"patientId\":\"" + otherPatient.getId()
                                + "\",\"replaceExisting\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_ACTIVE"));
        assertThat(assignmentRepository.findById(assignment.getId()).orElseThrow().isActive()).isTrue();   // unchanged

        mockMvc.perform(op.with(post("/api/v1/sessions/" + sessionId + "/end")));
        mockMvc.perform(op.with(post("/api/v1/device-assignments/" + assignment.getId() + "/release")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    // ------------------------------------------------------------------ database safeguard (V3)

    @Test
    void databaseRejectsASecondActiveSessionEvenIfJavaWereBypassed() {
        String insert = "insert into monitoring_sessions (id, session_code, patient_id, device_assignment_id, started_by, "
                + "started_at, status, created_at) values (?, ?, ?, ?, ?, now(), 'ACTIVE', now())";
        jdbcTemplate.update(insert, UUID.randomUUID(), "SES-TEST-1", patient.getId(), assignment.getId(), operatorUser.getId());
        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), "SES-TEST-2", patient.getId(),
                assignment.getId(), operatorUser.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_monitoring_sessions_active");
        // ENDED rows are not limited
        jdbcTemplate.update("update monitoring_sessions set status='ENDED', ended_at=now(), ended_by=started_by");
        jdbcTemplate.update(insert, UUID.randomUUID(), "SES-TEST-3", patient.getId(), assignment.getId(), operatorUser.getId());
    }
}
