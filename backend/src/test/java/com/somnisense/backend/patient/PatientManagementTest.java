package com.somnisense.backend.patient;

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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 5: patient registration/profile endpoints through the real HTTP stack, against somnisense_test_db. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatientManagementTest {

    private static final String PW = "a-test-password-123";
    private static final String CODE_PATTERN = "^PT-[2-9A-HJ-NP-Z]{8}$";

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

    private MvcResult create(LoggedInClient who, String body) throws Exception {
        return mockMvc.perform(who.with(post("/api/v1/patients"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String createdId(String body) throws Exception {
        MvcResult r = create(op, body);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    // ------------------------------------------------------------------ create

    @Test
    void operatorRegistersPatientAndBackendGeneratesTheCode() throws Exception {
        mockMvc.perform(op.with(post("/api/v1/patients")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"  Synthetic Patient 01 \",\"sourceType\":\"SIMULATED\","
                                + "\"patientCode\":\"PT-HACKED00\"}"))   // a client-sent code must be ignored
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/patients/")))
                .andExpect(jsonPath("$.patientCode").value(matchesPattern(CODE_PATTERN)))
                .andExpect(jsonPath("$.patientCode").value(not("PT-HACKED00")))
                .andExpect(jsonPath("$.fullName").value("Synthetic Patient 01"))
                .andExpect(jsonPath("$.sourceType").value("SIMULATED"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdBy").value(operator.getId().toString()))
                .andExpect(jsonPath("$.contact").value(nullValue()))
                .andExpect(jsonPath("$.medicalDetails").value(nullValue()));
    }

    @Test
    void createWithContactAndMedicalDetails() throws Exception {
        String body = """
                {"fullName":"Test Patient","alias":"TP","dateOfBirth":"1980-05-17","gender":"FEMALE",
                 "sourceType":"PHYSICAL",
                 "contact":{"phone":"+91 98300 12345","email":"Patient@Example.Local","city":"Kolkata","state":"  "},
                 "medicalDetails":{"sleepDisorder":"Suspected OSA","notes":"Synthetic note"}}
                """;
        mockMvc.perform(op.with(post("/api/v1/patients")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dateOfBirth").value("1980-05-17"))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.contact.phone").value("+91 98300 12345"))
                .andExpect(jsonPath("$.contact.email").value("patient@example.local"))
                .andExpect(jsonPath("$.contact.state").value(nullValue()))       // blank -> null
                .andExpect(jsonPath("$.medicalDetails.sleepDisorder").value("Suspected OSA"))
                .andExpect(jsonPath("$.medicalDetails.updatedBy").value(operator.getId().toString()))
                .andExpect(jsonPath("$.medicalDetails.updatedAt").exists());

        Map<String, Object> audit = jdbcTemplate.queryForMap("select details::text as d from audit_logs where action='PATIENT_CREATED'");
        assertThat(audit.get("d").toString())
                .contains("\"withContact\": true", "\"withMedicalDetails\": true", "PHYSICAL")
                .doesNotContain("Test Patient", "OSA", "Kolkata", "1980");
    }

    @Test
    void invalidFieldsAreReportedIncludingNestedOnes() throws Exception {
        String body = """
                {"dateOfBirth":"2999-01-01",
                 "contact":{"phone":"call me maybe","email":"nope"},
                 "medicalDetails":{"sleepDisorder":"%s"}}
                """.formatted("x".repeat(151));
        MvcResult r = create(op, body);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        List<String> fields = JsonPath.read(r.getResponse().getContentAsString(), "$.fieldErrors[*].field");
        assertThat(fields).contains("fullName", "sourceType", "dateOfBirth", "contact.phone", "contact.email",
                "medicalDetails.sleepDisorder");
    }

    @Test
    void birthDateBefore1900IsRejected() throws Exception {
        MvcResult r = create(op, "{\"fullName\":\"Old\",\"sourceType\":\"PHYSICAL\",\"dateOfBirth\":\"1850-01-01\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("INVALID_REQUEST", "1900-01-01");
    }

    @Test
    void unknownEnumValuesAreMalformed() throws Exception {
        assertThat(create(op, "{\"fullName\":\"A\",\"sourceType\":\"HARDWARE\"}").getResponse().getContentAsString())
                .contains("MALFORMED_REQUEST");
        assertThat(create(op, "{\"fullName\":\"A\",\"sourceType\":\"PHYSICAL\",\"gender\":\"M\"}").getResponse().getContentAsString())
                .contains("MALFORMED_REQUEST");
    }

    @Test
    void notLoggedInGets401() throws Exception {
        mockMvc.perform(get("/api/v1/patients")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ list / get

    @Test
    void listIsFilteredPagedAndHidesSensitiveFields() throws Exception {
        createdId("{\"fullName\":\"Alice Sim\",\"sourceType\":\"SIMULATED\",\"dateOfBirth\":\"1970-01-01\","
                + "\"medicalDetails\":{\"notes\":\"secret-note\"}}");
        createdId("{\"fullName\":\"Bob Physical\",\"sourceType\":\"PHYSICAL\",\"contact\":{\"phone\":\"12345\"}}");
        String carolId = createdId("{\"fullName\":\"Carol Sim\",\"alias\":\"Night Owl\",\"sourceType\":\"SIMULATED\"}");

        mockMvc.perform(op.with(get("/api/v1/patients")).param("sourceType", "SIMULATED")
                        .param("sort", "fullName").param("direction", "asc").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].fullName").value("Alice Sim"))
                .andExpect(jsonPath("$.content[0].patientCode").value(matchesPattern(CODE_PATTERN)))
                .andExpect(jsonPath("$.content[0].dateOfBirth").doesNotExist())
                .andExpect(jsonPath("$.content[0].contact").doesNotExist())
                .andExpect(content().string(not(containsString("secret-note"))));

        mockMvc.perform(op.with(get("/api/v1/patients")).param("q", "night"))   // alias search
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(carolId));

        String carolCode = JsonPath.read(mockMvc.perform(op.with(get("/api/v1/patients/" + carolId)))
                .andReturn().getResponse().getContentAsString(), "$.patientCode");
        mockMvc.perform(op.with(get("/api/v1/patients")).param("q", carolCode.substring(0, 6).toLowerCase()))
                .andExpect(jsonPath("$.content[?(@.id=='" + carolId + "')]").exists());   // code search, case-insensitive

        mockMvc.perform(op.with(get("/api/v1/patients")).param("sort", "dateOfBirth"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getUnknownPatientIs404() throws Exception {
        mockMvc.perform(op.with(get("/api/v1/patients/" + java.util.UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ update

    @Test
    void updateProfileChangesEditableFieldsOnlyAndAuditsFieldNames() throws Exception {
        String id = createdId("{\"fullName\":\"Before\",\"sourceType\":\"SIMULATED\"}");
        String code = JsonPath.read(mockMvc.perform(op.with(get("/api/v1/patients/" + id)))
                .andReturn().getResponse().getContentAsString(), "$.patientCode");

        mockMvc.perform(op.with(put("/api/v1/patients/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"After\",\"gender\":\"OTHER\",\"sourceType\":\"PHYSICAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("After"))
                .andExpect(jsonPath("$.gender").value("OTHER"))
                .andExpect(jsonPath("$.sourceType").value("SIMULATED"))   // cannot be changed
                .andExpect(jsonPath("$.patientCode").value(code));        // cannot be changed

        // same values again -> no change, no extra audit row
        mockMvc.perform(op.with(put("/api/v1/patients/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"After\",\"gender\":\"OTHER\"}")).andExpect(status().isOk());

        List<String> details = jdbcTemplate.queryForList(
                "select details::text from audit_logs where action='PATIENT_UPDATED'", String.class);
        assertThat(details).hasSize(1);
        assertThat(details.get(0)).contains("fullName", "gender").doesNotContain("After", "Before", "OTHER");
    }

    @Test
    void contactAndMedicalDetailsAreUpserted() throws Exception {
        String id = createdId("{\"fullName\":\"P\",\"sourceType\":\"PHYSICAL\"}");

        mockMvc.perform(op.with(put("/api/v1/patients/" + id + "/contact")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"111\",\"city\":\"Pune\"}"))
                .andExpect(jsonPath("$.contact.city").value("Pune"));
        mockMvc.perform(op.with(put("/api/v1/patients/" + id + "/contact")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"222\"}"))   // PUT replaces: city is cleared
                .andExpect(jsonPath("$.contact.phone").value("222"))
                .andExpect(jsonPath("$.contact.city").value(nullValue()));
        assertThat(jdbcTemplate.queryForObject("select count(*) from patient_contacts", Integer.class)).isEqualTo(1);

        mockMvc.perform(admin.with(put("/api/v1/patients/" + id + "/medical-details")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"medications\":\"none\"}"))
                .andExpect(jsonPath("$.medicalDetails.medications").value("none"))
                .andExpect(jsonPath("$.medicalDetails.updatedBy").exists());
        assertThat(jdbcTemplate.queryForObject("select count(*) from patient_medical_details", Integer.class)).isEqualTo(1);

        String auditText = String.join(" ", jdbcTemplate.queryForList(
                "select coalesce(details::text,'') from audit_logs", String.class));
        assertThat(auditText).doesNotContain("Pune", "222", "none");
    }

    // ------------------------------------------------------------------ status

    @Test
    void onlyAdminChangesStatus() throws Exception {
        String id = createdId("{\"fullName\":\"P\",\"sourceType\":\"SIMULATED\"}");

        mockMvc.perform(op.with(patch("/api/v1/patients/" + id + "/status")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(admin.with(patch("/api/v1/patients/" + id + "/status")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "select details->>'from' as f, details->>'to' as t from audit_logs where action='PATIENT_STATUS_CHANGED'");
        assertThat(audit).containsEntry("f", "ACTIVE").containsEntry("t", "INACTIVE");

        mockMvc.perform(op.with(get("/api/v1/patients")).param("status", "INACTIVE"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
