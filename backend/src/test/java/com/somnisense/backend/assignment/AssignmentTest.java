package com.somnisense.backend.assignment;

import com.jayway.jsonpath.JsonPath;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.entity.EnrollmentStatus;
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

import java.sql.Timestamp;
import java.time.Instant;
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

/** Stage 7: device-to-patient assignments through the real HTTP stack, against somnisense_test_db. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssignmentTest {

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
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User operatorUser;
    private LoggedInClient admin;
    private LoggedInClient op;
    private Patient simPatient1;
    private Patient simPatient2;
    private Patient physPatient;
    private Device simDevice1;
    private Device simDevice2;
    private Device physDevice;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(jdbcTemplate);
        User adminUser = userRepository.save(new User("admin", "admin@test.local", passwordEncoder.encode(PW), Role.ADMIN));
        operatorUser = userRepository.save(new User("operator1", "op1@test.local", passwordEncoder.encode(PW), Role.OPERATOR));
        admin = LoggedInClient.login(mockMvc, "admin", PW);
        op = LoggedInClient.login(mockMvc, "operator1", PW);
        UUID by = adminUser.getId();
        simPatient1 = patientRepository.save(new Patient("PT-SIM00001", "Sim One", null, null, null, SourceType.SIMULATED, by));
        simPatient2 = patientRepository.save(new Patient("PT-SIM00002", "Sim Two", null, null, null, SourceType.SIMULATED, by));
        physPatient = patientRepository.save(new Patient("PT-PHY00001", "Phys One", null, null, null, SourceType.PHYSICAL, by));
        simDevice1 = deviceRepository.save(new Device("DEV-SIM1", SourceType.SIMULATED, null, null, by));   // starts ENROLLED
        simDevice2 = deviceRepository.save(new Device("DEV-SIM2", SourceType.SIMULATED, null, null, by));
        physDevice = deviceRepository.save(new Device("OXI-PHYS1", SourceType.PHYSICAL, null, null, by));   // starts PENDING
    }

    private MvcResult assign(UUID deviceId, UUID patientId, boolean replace) throws Exception {
        return mockMvc.perform(op.with(post("/api/v1/device-assignments")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\",\"patientId\":\"" + patientId
                                + "\",\"replaceExisting\":" + replace + "}"))
                .andReturn();
    }

    private String assignOk(Device d, Patient p) throws Exception {
        MvcResult r = assign(d.getId(), p.getId(), false);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private int activeCount() {
        return jdbcTemplate.queryForObject("select count(*) from device_assignments where released_at is null", Integer.class);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void assignShowsUpOnAssignmentDeviceAndPatient() throws Exception {
        MvcResult r = assign(simDevice1.getId(), simPatient1.getId(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(r.getResponse().getHeader("Location")).contains("/api/v1/device-assignments/");
        assertThat(body(r)).contains("\"active\":true", "\"deviceCode\":\"DEV-SIM1\"", "\"patientCode\":\"PT-SIM00001\"",
                "\"assignedBy\":\"" + operatorUser.getId() + "\"", "\"releasedAt\":null");

        mockMvc.perform(op.with(get("/api/v1/devices/" + simDevice1.getId())))
                .andExpect(jsonPath("$.currentAssignment.patientCode").value("PT-SIM00001"));
        mockMvc.perform(op.with(get("/api/v1/patients/" + simPatient1.getId())))
                .andExpect(jsonPath("$.currentDevice.deviceCode").value("DEV-SIM1"))
                .andExpect(jsonPath("$.currentDevice.connectivityStatus").value("UNKNOWN"));
        mockMvc.perform(op.with(get("/api/v1/patients")).param("sort", "patientCode").param("direction", "asc"))
                .andExpect(jsonPath("$.content[?(@.patientCode=='PT-SIM00001')].currentDevice.deviceCode").value("DEV-SIM1"))
                .andExpect(jsonPath("$.content[?(@.patientCode=='PT-SIM00002')].currentDevice").value(org.hamcrest.Matchers.contains(nullValue())));
    }

    @Test
    void patientListShowsLiveConnectivityOfTheAssignedDevice() throws Exception {
        assignOk(simDevice1, simPatient1);
        jdbcTemplate.update("update devices set last_seen_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(5)), simDevice1.getId());
        mockMvc.perform(op.with(get("/api/v1/patients/" + simPatient1.getId())))
                .andExpect(jsonPath("$.currentDevice.connectivityStatus").value("CONNECTED"))
                .andExpect(jsonPath("$.currentDevice.lastSeenAt").exists());
    }

    @Test
    void assigningTheSamePairAgainIsANoOp() throws Exception {
        String id = assignOk(simDevice1, simPatient1);
        MvcResult again = assign(simDevice1.getId(), simPatient1.getId(), false);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(body(again), "$.id")).isEqualTo(id);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action='DEVICE_ASSIGNED'", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ eligibility

    @Test
    void deviceTypeMustMatchPatientSource() throws Exception {
        MvcResult r = assign(simDevice1.getId(), physPatient.getId(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r)).contains("SOURCE_TYPE_MISMATCH");
    }

    @Test
    void deviceMustBeEnrolledAndActive() throws Exception {
        MvcResult pending = assign(physDevice.getId(), physPatient.getId(), false);
        assertThat(pending.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(pending)).contains("DEVICE_NOT_AVAILABLE", "PENDING");

        mockMvc.perform(op.with(patch("/api/v1/devices/" + physDevice.getId() + "/enrollment"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enrollmentStatus\":\"ENROLLED\"}"));
        assignOk(physDevice, physPatient);

        mockMvc.perform(admin.with(post("/api/v1/devices/" + simDevice2.getId() + "/deactivate"))).andExpect(status().isOk());
        assertThat(body(assign(simDevice2.getId(), simPatient2.getId(), false))).contains("DEVICE_NOT_AVAILABLE", "inactive");
    }

    @Test
    void patientMustBeActive() throws Exception {
        mockMvc.perform(admin.with(patch("/api/v1/patients/" + simPatient1.getId() + "/status"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}")).andExpect(status().isOk());
        MvcResult r = assign(simDevice1.getId(), simPatient1.getId(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r)).contains("PATIENT_NOT_ACTIVE");
    }

    @Test
    void unknownIdsAre404AndMissingIdsAre400() throws Exception {
        assertThat(assign(UUID.randomUUID(), simPatient1.getId(), false).getResponse().getStatus()).isEqualTo(404);
        assertThat(assign(simDevice1.getId(), UUID.randomUUID(), false).getResponse().getStatus()).isEqualTo(404);
        mockMvc.perform(op.with(post("/api/v1/device-assignments")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------ conflicts and reassignment

    @Test
    void deviceAlreadyAssignedNeedsConfirmationThenReassigns() throws Exception {
        String first = assignOk(simDevice1, simPatient1);

        MvcResult conflict = assign(simDevice1.getId(), simPatient2.getId(), false);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(conflict)).contains("DEVICE_ALREADY_ASSIGNED", "PT-SIM00001", "replaceExisting=true");
        assertThat(activeCount()).isEqualTo(1);

        MvcResult replaced = assign(simDevice1.getId(), simPatient2.getId(), true);
        assertThat(replaced.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(replaced)).contains("\"patientCode\":\"PT-SIM00002\"");

        mockMvc.perform(op.with(get("/api/v1/device-assignments/" + first)))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.releasedBy").value(operatorUser.getId().toString()))
                .andExpect(jsonPath("$.releasedAt").exists());
        mockMvc.perform(op.with(get("/api/v1/device-assignments")).param("deviceId", simDevice1.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(2))          // history kept
                .andExpect(jsonPath("$.content[0].active").value(true));   // newest first
        assertThat(activeCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action='DEVICE_RELEASED' and details->>'reason'='REASSIGNED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void patientAlreadyHasADeviceNeedsConfirmation() throws Exception {
        assignOk(simDevice1, simPatient1);
        MvcResult conflict = assign(simDevice2.getId(), simPatient1.getId(), false);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(conflict)).contains("PATIENT_ALREADY_HAS_DEVICE", "DEV-SIM1");

        assertThat(assign(simDevice2.getId(), simPatient1.getId(), true).getResponse().getStatus()).isEqualTo(201);
        mockMvc.perform(op.with(get("/api/v1/patients/" + simPatient1.getId())))
                .andExpect(jsonPath("$.currentDevice.deviceCode").value("DEV-SIM2"));
        mockMvc.perform(op.with(get("/api/v1/devices/" + simDevice1.getId())))
                .andExpect(jsonPath("$.currentAssignment").value(nullValue()));
    }

    @Test
    void replacingCanReleaseTwoOldAssignmentsAtOnce() throws Exception {
        assignOk(simDevice1, simPatient1);   // device 1 busy
        assignOk(simDevice2, simPatient2);   // patient 2 busy
        assertThat(assign(simDevice1.getId(), simPatient2.getId(), true).getResponse().getStatus()).isEqualTo(201);
        assertThat(activeCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from device_assignments where released_at is not null", Integer.class)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ release

    @Test
    void releaseEndsTheAssignmentAndRepeatingIsSafe() throws Exception {
        String id = assignOk(simDevice1, simPatient1);
        mockMvc.perform(op.with(post("/api/v1/device-assignments/" + id + "/release")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(op.with(post("/api/v1/device-assignments/" + id + "/release")))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action='DEVICE_RELEASED'", Integer.class)).isEqualTo(1);
        mockMvc.perform(op.with(get("/api/v1/patients/" + simPatient1.getId())))
                .andExpect(jsonPath("$.currentDevice").value(nullValue()));
        mockMvc.perform(op.with(post("/api/v1/device-assignments/" + UUID.randomUUID() + "/release")))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ deactivation blocked while assigned

    @Test
    void assignedDeviceAndPatientCannotBeDeactivatedUntilReleased() throws Exception {
        String id = assignOk(simDevice1, simPatient1);

        mockMvc.perform(admin.with(post("/api/v1/devices/" + simDevice1.getId() + "/deactivate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_IN_USE"))
                .andExpect(jsonPath("$.message").value(containsString("PT-SIM00001")));
        mockMvc.perform(admin.with(patch("/api/v1/patients/" + simPatient1.getId() + "/status"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PATIENT_HAS_ACTIVE_DEVICE"))
                .andExpect(jsonPath("$.message").value(containsString("DEV-SIM1")));

        mockMvc.perform(op.with(post("/api/v1/device-assignments/" + id + "/release"))).andExpect(status().isOk());

        mockMvc.perform(admin.with(post("/api/v1/devices/" + simDevice1.getId() + "/deactivate"))).andExpect(status().isOk());
        mockMvc.perform(admin.with(patch("/api/v1/patients/" + simPatient1.getId() + "/status"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ database safeguard (V2)

    @Test
    void databaseRejectsASecondActiveRowEvenIfJavaWereBypassed() {
        UUID by = operatorUser.getId();
        String insert = "insert into device_assignments (id, device_id, patient_id, assigned_by, assigned_at) "
                + "values (?, ?, ?, ?, now())";
        jdbcTemplate.update(insert, UUID.randomUUID(), simDevice1.getId(), simPatient1.getId(), by);

        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), simDevice1.getId(), simPatient2.getId(), by))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_device_assignments_active_device");
        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), simDevice2.getId(), simPatient1.getId(), by))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_device_assignments_active_patient");
    }
}
