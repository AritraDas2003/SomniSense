package com.somnisense.backend.device;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 6: device registration/lifecycle endpoints through the real HTTP stack, against somnisense_test_db. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceManagementTest {

    private static final String PW = "a-test-password-123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User operator;
    private LoggedInClient admin;
    private LoggedInClient op;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(jdbcTemplate);
        userRepository.save(new User("admin", "admin@test.local", passwordEncoder.encode(PW), Role.ADMIN));
        operator = userRepository.save(new User("operator1", "op1@test.local", passwordEncoder.encode(PW), Role.OPERATOR));
        admin = LoggedInClient.login(mockMvc, "admin", PW);
        op = LoggedInClient.login(mockMvc, "operator1", PW);
    }

    private MvcResult register(String body) throws Exception {
        return mockMvc.perform(op.with(post("/api/v1/devices")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private String registeredId(String body) throws Exception {
        MvcResult r = register(body);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    // ------------------------------------------------------------------ register

    @Test
    void physicalDeviceWithStaffCodeStartsPendingAndUnknown() throws Exception {
        mockMvc.perform(op.with(post("/api/v1/devices")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceCode\":\" oxi-0042 \",\"deviceType\":\"PHYSICAL\",\"modelName\":\"Contec CMS50D\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceCode").value("OXI-0042"))
                .andExpect(jsonPath("$.deviceType").value("PHYSICAL"))
                .andExpect(jsonPath("$.enrollmentStatus").value("PENDING"))
                .andExpect(jsonPath("$.connectivityStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.lastSeenAt").doesNotExist())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.registeredBy").value(operator.getId().toString()));
    }

    @Test
    void simulatedDeviceWithoutCodeGetsGeneratedCodeAndIsEnrolled() throws Exception {
        mockMvc.perform(op.with(post("/api/v1/devices")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceType\":\"SIMULATED\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceCode").value(matchesPattern("^DEV-[2-9A-HJ-NP-Z]{8}$")))
                .andExpect(jsonPath("$.enrollmentStatus").value("ENROLLED"));

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "select details->>'codeSource' as src, details->>'deviceType' as t from audit_logs where action='DEVICE_REGISTERED'");
        assertThat(audit).containsEntry("src", "GENERATED").containsEntry("t", "SIMULATED");
    }

    @Test
    void duplicateCodeIsConflictIgnoringCase() throws Exception {
        registeredId("{\"deviceCode\":\"OXI-1\",\"deviceType\":\"PHYSICAL\"}");
        MvcResult dup = register("{\"deviceCode\":\"oxi-1\",\"deviceType\":\"SIMULATED\"}");
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        assertThat(dup.getResponse().getContentAsString()).contains("Device code is already registered");
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        MvcResult bad = register("{\"deviceCode\":\"has space!\",\"modelName\":\"" + "m".repeat(101) + "\"}");
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        List<String> fields = JsonPath.read(bad.getResponse().getContentAsString(), "$.fieldErrors[*].field");
        assertThat(fields).contains("deviceCode", "deviceType", "modelName");

        assertThat(register("{\"deviceType\":\"HARDWARE\"}").getResponse().getContentAsString()).contains("MALFORMED_REQUEST");
    }

    @Test
    void responsesNeverContainSecrets() throws Exception {
        String body = register("{\"deviceType\":\"PHYSICAL\",\"secret\":\"abc\",\"credential\":\"xyz\"}")
                .getResponse().getContentAsString();
        assertThat(body.toLowerCase()).doesNotContain("secret", "credential", "token", "password", "abc", "xyz");
    }

    // ------------------------------------------------------------------ list / connectivity

    private void setLastSeen(String id, Instant when) {
        jdbcTemplate.update("update devices set last_seen_at = ? where id = ?",
                when == null ? null : Timestamp.from(when), UUID.fromString(id));
    }

    @Test
    void connectivityIsComputedFromLastSeenAndFilterAgrees() throws Exception {
        String fresh = registeredId("{\"deviceCode\":\"FRESH\",\"deviceType\":\"SIMULATED\"}");
        String late = registeredId("{\"deviceCode\":\"LATE\",\"deviceType\":\"SIMULATED\"}");
        String gone = registeredId("{\"deviceCode\":\"GONE\",\"deviceType\":\"PHYSICAL\"}");
        String never = registeredId("{\"deviceCode\":\"NEVER\",\"deviceType\":\"PHYSICAL\"}");
        Instant now = Instant.now();
        setLastSeen(fresh, now.minusSeconds(5));
        setLastSeen(late, now.minusSeconds(60));
        setLastSeen(gone, now.minusSeconds(300));

        Map<String, String> expected = Map.of(fresh, "CONNECTED", late, "STALE", gone, "DISCONNECTED", never, "UNKNOWN");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            mockMvc.perform(op.with(get("/api/v1/devices/" + e.getKey())))
                    .andExpect(jsonPath("$.connectivityStatus").value(e.getValue()));
            mockMvc.perform(op.with(get("/api/v1/devices")).param("connectivity", e.getValue()))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(e.getKey()))
                    .andExpect(jsonPath("$.content[0].connectivityStatus").value(e.getValue()));
        }
    }

    @Test
    void listFiltersAndSearch() throws Exception {
        registeredId("{\"deviceCode\":\"OXI-A\",\"deviceType\":\"PHYSICAL\",\"serialNumber\":\"SN-998877\"}");
        registeredId("{\"deviceType\":\"SIMULATED\"}");
        registeredId("{\"deviceType\":\"SIMULATED\"}");

        mockMvc.perform(op.with(get("/api/v1/devices")).param("deviceType", "SIMULATED"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(op.with(get("/api/v1/devices")).param("enrollmentStatus", "PENDING"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(op.with(get("/api/v1/devices")).param("q", "998877"))   // serial search
                .andExpect(jsonPath("$.content[0].deviceCode").value("OXI-A"));
        mockMvc.perform(op.with(get("/api/v1/devices")).param("sort", "deviceCode").param("direction", "asc"))
                .andExpect(jsonPath("$.content[0].deviceCode").value(matchesPattern("^DEV-.*")));
        mockMvc.perform(op.with(get("/api/v1/devices")).param("sort", "serialNumber"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ update / enrollment / activation

    @Test
    void updateChangesOnlyModelAndSerial() throws Exception {
        String id = registeredId("{\"deviceCode\":\"KEEP-ME\",\"deviceType\":\"PHYSICAL\",\"modelName\":\"Old\"}");
        mockMvc.perform(op.with(put("/api/v1/devices/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"New\",\"serialNumber\":\"S1\",\"deviceCode\":\"HACK\",\"deviceType\":\"SIMULATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").value("New"))
                .andExpect(jsonPath("$.serialNumber").value("S1"))
                .andExpect(jsonPath("$.deviceCode").value("KEEP-ME"))
                .andExpect(jsonPath("$.deviceType").value("PHYSICAL"));
        mockMvc.perform(op.with(put("/api/v1/devices/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"modelName\":\"New\",\"serialNumber\":\"S1\"}"));   // no change

        List<String> audits = jdbcTemplate.queryForList(
                "select details::text from audit_logs where action='DEVICE_UPDATED'", String.class);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).contains("modelName", "serialNumber");
    }

    @Test
    void operatorChangesEnrollment() throws Exception {
        String id = registeredId("{\"deviceType\":\"PHYSICAL\"}");
        mockMvc.perform(op.with(patch("/api/v1/devices/" + id + "/enrollment")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enrollmentStatus\":\"ENROLLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentStatus").value("ENROLLED"));
        mockMvc.perform(op.with(patch("/api/v1/devices/" + id + "/enrollment")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enrollmentStatus\":\"ENROLLED\"}"));   // repeat = no-op
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "select details->>'from' as f, details->>'to' as t from audit_logs where action='DEVICE_ENROLLMENT_CHANGED'");
        assertThat(audit).containsEntry("f", "PENDING").containsEntry("t", "ENROLLED");
    }

    @Test
    void onlyAdminDeactivatesAndReactivates() throws Exception {
        String id = registeredId("{\"deviceType\":\"SIMULATED\"}");
        mockMvc.perform(op.with(post("/api/v1/devices/" + id + "/deactivate"))).andExpect(status().isForbidden());

        mockMvc.perform(admin.with(post("/api/v1/devices/" + id + "/deactivate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(op.with(get("/api/v1/devices")).param("active", "false"))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(op.with(post("/api/v1/devices/" + id + "/reactivate"))).andExpect(status().isForbidden());
        mockMvc.perform(admin.with(post("/api/v1/devices/" + id + "/reactivate")))
                .andExpect(jsonPath("$.active").value(true));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action in ('DEVICE_DEACTIVATED','DEVICE_REACTIVATED')", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void unknownDeviceIs404AndAnonymousIs401() throws Exception {
        mockMvc.perform(op.with(get("/api/v1/devices/" + UUID.randomUUID()))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/devices")).andExpect(status().isUnauthorized());
    }
}
