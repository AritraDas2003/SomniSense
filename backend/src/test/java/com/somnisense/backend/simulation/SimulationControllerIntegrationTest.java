package com.somnisense.backend.simulation;

import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.simulation.config.SimulationProperties;
import com.somnisense.backend.support.LoggedInClient;
import com.somnisense.backend.support.TestDatabase;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP endpoint tests for SimulationController + X-Ingestion-Key ingestion fencing integration
 * against somnisense_test_db.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SimulationControllerIntegrationTest {

    private static final String PW = "a-test-password-123";
    private static final String INGESTION_KEY = "test-ingestion-key-0123456789abcdef-0123456789";

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
    private MonitoringSessionRepository sessionRepository;
    @Autowired
    private SimulationProperties simulationProperties;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpServer workerStub;
    private String originalWorkerUrl;
    private LoggedInClient admin;
    private LoggedInClient operator;
    private MonitoringSession session;
    private Device device;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.clean(jdbcTemplate);
        originalWorkerUrl = simulationProperties.getWorkerBaseUrl();

        workerStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workerStub.createContext("/internal/sim/", exchange -> {
            byte[] resp = ("{\"simulationRunId\":\"" + UUID.randomUUID()
                    + "\",\"sessionId\":\"" + UUID.randomUUID()
                    + "\",\"workerInstanceId\":\"sim-worker-http-1\",\"status\":\"RUNNING\",\"lastSubmittedStep\":0}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        workerStub.start();
        simulationProperties.setWorkerBaseUrl("http://127.0.0.1:" + workerStub.getAddress().getPort());

        User adminUser = userRepository.save(new User("simadmin", "simadmin@test.local",
                passwordEncoder.encode(PW), Role.ADMIN));
        userRepository.save(new User("simop", "simop@test.local",
                passwordEncoder.encode(PW), Role.OPERATOR));

        admin = LoggedInClient.login(mockMvc, "simadmin", PW);
        operator = LoggedInClient.login(mockMvc, "simop", PW);

        UUID by = adminUser.getId();
        Patient patient = patientRepository.save(new Patient("PT-CTRL01", "Ctrl Patient", null, null, null,
                SourceType.SIMULATED, by));
        device = deviceRepository.save(new Device("DEV-CTRL01", SourceType.SIMULATED, "SimOx", "SN-C1", by));
        DeviceAssignment assignment = assignmentRepository.save(new DeviceAssignment(device.getId(), patient.getId(), by));
        session = sessionRepository.save(new MonitoringSession("SES-CTRL-01", patient.getId(),
                assignment.getId(), by, Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS)));
    }

    @AfterEach
    void tearDown() {
        simulationProperties.setWorkerBaseUrl(originalWorkerUrl);
        if (workerStub != null) {
            workerStub.stop(0);
        }
    }

    @Test
    void enforcesRbacAndDrivesFullStartPauseResumeStopAndFencedIngestionRoundTrip() throws Exception {
        // Obtain real XSRF-TOKEN cookie without SecurityMockMvcRequestPostProcessors.csrf() (never mutate singleton CsrfFilter)
        var csrfMvc = mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie xsrfCookie = Arrays.stream(csrfMvc.getResponse().getCookies())
                .filter(c -> "XSRF-TOKEN".equals(c.getName()) && c.getMaxAge() != 0)
                .findFirst()
                .orElseThrow();

        // Missing or invalid CSRF token on authenticated session is rejected with 403 CSRF_TOKEN_INVALID
        mockMvc.perform(post("/api/v1/sessions/{id}/simulation/start", session.getId())
                        .session(operator.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"normal_sleep\",\"seed\":42}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        mockMvc.perform(post("/api/v1/sessions/{id}/simulation/start", session.getId())
                        .session(operator.session())
                        .cookie(xsrfCookie)
                        .header("X-XSRF-TOKEN", "invalid-csrf-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"normal_sleep\",\"seed\":42}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        // Unauthenticated with valid CSRF cookie/header -> 401 UNAUTHORIZED
        mockMvc.perform(post("/api/v1/sessions/{id}/simulation/start", session.getId())
                        .cookie(xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrfCookie.getValue()))
                .andExpect(status().isUnauthorized());

        // Non-ADMIN / non-OPERATOR GUEST role with valid CSRF cookie/header -> 403 FORBIDDEN
        mockMvc.perform(post("/api/v1/sessions/{id}/simulation/start", session.getId())
                        .with(user("readonly").roles("GUEST"))
                        .cookie(xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"normal_sleep\",\"seed\":42}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // OPERATOR starts simulation with valid session + XSRF-TOKEN cookie/header -> 201 CREATED
        var startMvc = mockMvc.perform(operator.with(post("/api/v1/sessions/{id}/simulation/start", session.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"normal_sleep\",\"seed\":42,\"cadenceHz\":1.0,\"playbackSpeed\":1.0}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.workerGeneration").value(1))
                .andReturn();

        String runId = com.jayway.jsonpath.JsonPath.read(startMvc.getResponse().getContentAsString(), "$.id");
        Instant ts1 = session.getStartedAt().plusSeconds(1);

        // Worker-style HTTP ingestion batch passes fencing with gen=1
        mockMvc.perform(post("/api/v1/ingestion/readings")
                        .header("X-Ingestion-Key", INGESTION_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceCode": "%s",
                                  "sessionCode": "%s",
                                  "simulationRunId": "%s",
                                  "workerGeneration": 1,
                                  "workerInstanceId": "sim-worker-http-1",
                                  "readings": [
                                    {
                                      "readingKey": "%s-%s-00000001",
                                      "measuredAt": "%s",
                                      "spo2": 97.45,
                                      "heartRate": 64.10,
                                      "signalQuality": null
                                    }
                                  ]
                                }
                                """.formatted(device.getDeviceCode(), session.getSessionCode(), runId,
                                device.getDeviceCode(), session.getSessionCode(), ts1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));

        // Pause -> 200 PAUSED
        mockMvc.perform(operator.with(post("/api/v1/sessions/{id}/simulation/pause", session.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.lastStep").value(1));

        // Resume -> 200 RUNNING with gen=2
        mockMvc.perform(admin.with(post("/api/v1/sessions/{id}/simulation/resume", session.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.workerGeneration").value(2));

        // Orphan gen=1 HTTP ingestion rejected with 409 STALE_WORKER_GENERATION
        Instant ts2 = session.getStartedAt().plusSeconds(2);
        mockMvc.perform(post("/api/v1/ingestion/readings")
                        .header("X-Ingestion-Key", INGESTION_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceCode": "%s",
                                  "sessionCode": "%s",
                                  "simulationRunId": "%s",
                                  "workerGeneration": 1,
                                  "workerInstanceId": "sim-worker-orphan",
                                  "readings": [
                                    {
                                      "readingKey": "%s-%s-00000002",
                                      "measuredAt": "%s",
                                      "spo2": 97.00,
                                      "heartRate": 64.00,
                                      "signalQuality": null
                                    }
                                  ]
                                }
                                """.formatted(device.getDeviceCode(), session.getSessionCode(), runId,
                                device.getDeviceCode(), session.getSessionCode(), ts2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_WORKER_GENERATION"));

        // Stop -> GET confirms STOPPED
        mockMvc.perform(admin.with(post("/api/v1/sessions/{id}/simulation/stop", session.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));

        mockMvc.perform(operator.with(get("/api/v1/sessions/{id}/simulation", session.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));
    }
}
