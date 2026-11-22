package com.somnisense.backend.ingestion;

import com.jayway.jsonpath.JsonPath;
import com.somnisense.backend.support.LoggedInClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 9: POST /api/v1/ingestion/readings through the real HTTP stack and security chain. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IngestionTest extends IngestionTestBase {

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ------------------------------------------------------------------ who may send

    @Test
    void missingOrWrongKeyIs401() throws Exception {
        String b = batch("DEV-ING1", reading("k1", Instant.now(), "96", "67"));
        MvcResult none = ingest(null, b);
        assertThat(none.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(none)).contains("\"code\":\"UNAUTHORIZED\"");
        assertThat(ingest("wrong-key-wrong-key-wrong-key-wrong-key", b).getResponse().getStatus()).isEqualTo(401);
        assertThat(storedReadings()).isZero();
    }

    @Test
    void aLoggedInBrowserCannotSubmitReadings() throws Exception {
        LoggedInClient staff = LoggedInClient.login(mockMvc, "admin", PW);   // session cookie + CSRF header
        mockMvc.perform(staff.with(post("/api/v1/ingestion/readings")).contentType(MediaType.APPLICATION_JSON)
                        .content(batch("DEV-ING1", reading("k1", Instant.now(), "96", "67"))))
                .andExpect(status().isUnauthorized());
        assertThat(storedReadings()).isZero();
    }

    @Test
    void ingestionKeyGivesNoAccessToStaffEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/patients").header("X-Ingestion-Key", KEY)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ storing

    @Test
    void validBatchIsStoredWithNullsKeptAndBothTimestamps() throws Exception {
        Instant t = Instant.now().minusSeconds(30);
        MvcResult r = ingest(KEY, batch("dev-ing1",   // device code is case-insensitive
                reading("k1", t, "96.5", "67"),
                reading("k2", t.plusSeconds(1), "null", "null"),   // dropout
                reading("k3", t.plusSeconds(2), "95", "null")));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"accepted\":3", "\"duplicates\":0", "\"rejected\":[]",
                "\"sessionCode\":\"SES-TEST-ING1\"", "\"deviceCode\":\"DEV-ING1\"");

        Map<String, Object> dropout = jdbcTemplate.queryForMap(
                "select spo2, heart_rate, measured_at, received_at from sensor_readings where reading_key='k2'");
        assertThat(dropout.get("spo2")).isNull();         // missing stays missing - never 0
        assertThat(dropout.get("heart_rate")).isNull();
        assertThat(dropout.get("measured_at")).isNotEqualTo(dropout.get("received_at"));
        assertThat(jdbcTemplate.queryForObject("select session_id from sensor_readings where reading_key='k1'", Object.class))
                .isEqualTo(session.getId());

        // data arrived -> the device is now CONNECTED
        LoggedInClient staff = LoggedInClient.login(mockMvc, "operator1", PW);
        mockMvc.perform(staff.with(get("/api/v1/devices/" + device.getId())))
                .andExpect(jsonPath("$.connectivityStatus").value("CONNECTED"))
                .andExpect(jsonPath("$.lastSeenAt").exists());
    }

    @Test
    void resendingABatchStoresNothingTwice() throws Exception {
        Instant t = Instant.now().minusSeconds(10);
        String b = batch("DEV-ING1", reading("k1", t, "96", "67"), reading("k2", t.plusSeconds(1), "97", "66"));
        ingest(KEY, b);
        MvcResult again = ingest(KEY, b);
        assertThat(body(again)).contains("\"accepted\":0", "\"duplicates\":2");
        assertThat(storedReadings()).isEqualTo(2);
    }

    @Test
    void sameKeyTwiceInOneBatchIsOneDuplicate() throws Exception {
        Instant t = Instant.now().minusSeconds(10);
        MvcResult r = ingest(KEY, batch("DEV-ING1", reading("same", t, "96", "67"), reading("same", t, "96", "67")));
        assertThat(body(r)).contains("\"accepted\":1", "\"duplicates\":1");
    }

    @Test
    void boundaryValuesAreAcceptedAndRoundedToTwoDecimals() throws Exception {
        Instant t = Instant.now().minusSeconds(10);
        MvcResult r = ingest(KEY, batch("DEV-ING1",
                reading("lo", t, "40", "20"), reading("hi", t, "100", "250"), reading("round", t, "96.456", "67.004")));
        assertThat(body(r)).contains("\"accepted\":3");
        assertThat(jdbcTemplate.queryForObject("select spo2 from sensor_readings where reading_key='round'", BigDecimal.class))
                .isEqualByComparingTo("96.46");
        assertThat(jdbcTemplate.queryForObject("select heart_rate from sensor_readings where reading_key='round'", BigDecimal.class))
                .isEqualByComparingTo("67.00");
    }

    // ------------------------------------------------------------------ partial acceptance

    @Test
    void badReadingsAreRejectedOneByOneNeverClamped() throws Exception {
        Instant t = Instant.now().minusSeconds(10);
        MvcResult r = ingest(KEY, batch("DEV-ING1",
                reading("good", t, "96", "67"),                                   // 0
                reading("spo2-high", t, "100.01", "67"),                          // 1
                reading("spo2-low", t, "39.99", "67"),                            // 2
                reading("hr-high", t, "96", "251"),                               // 3
                reading("hr-low", t, "96", "19"),                                 // 4
                reading("future", Instant.now().plusSeconds(60), "96", "67"),     // 5
                reading("too-early", sessionStart.minusSeconds(60), "96", "67"),  // 6
                reading(null, t, "96", "67"),                                     // 7
                reading("has space", t, "96", "67"),                              // 8
                reading("no-time", null, "96", "67"),                             // 9
                "null",                                                           // 10
                "{\"readingKey\":\"quality\",\"measuredAt\":\"" + t + "\",\"spo2\":96,\"heartRate\":67,\"signalQuality\":80}"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String json = body(r);
        assertThat(json).contains("\"accepted\":1");
        List<String> reasons = JsonPath.read(json, "$.rejected[*].reason");
        List<Integer> indexes = JsonPath.read(json, "$.rejected[*].index");
        assertThat(indexes).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(reasons).containsExactly("SPO2_OUT_OF_RANGE", "SPO2_OUT_OF_RANGE", "HEART_RATE_OUT_OF_RANGE",
                "HEART_RATE_OUT_OF_RANGE", "MEASURED_AT_IN_FUTURE", "MEASURED_AT_BEFORE_SESSION", "INVALID_READING_KEY",
                "INVALID_READING_KEY", "MISSING_MEASURED_AT", "READING_MISSING", "SIGNAL_QUALITY_NOT_SUPPORTED");
        assertThat(storedReadings()).isEqualTo(1);   // nothing clamped and stored as 100 / 40
    }

    @Test
    void smallClockDriftIsTolerated() throws Exception {
        MvcResult r = ingest(KEY, batch("DEV-ING1",
                reading("ahead", Instant.now().plusSeconds(5), "96", "67"),
                reading("early", sessionStart.minusSeconds(5), "96", "67")));
        assertThat(body(r)).contains("\"accepted\":2");
    }

    @Test
    void allRejectedDoesNotMarkTheDeviceAsSeen() throws Exception {
        ingest(KEY, batch("DEV-ING1", reading("bad", Instant.now(), "150", "67")));
        assertThat(jdbcTemplate.queryForObject("select last_seen_at from devices where id = ?", Object.class, device.getId()))
                .isNull();
    }

    // ------------------------------------------------------------------ routing

    @Test
    void routingErrors() throws Exception {
        String one = reading("k1", Instant.now(), "96", "67");
        assertThat(ingest(KEY, batch("DEV-NOPE", one)).getResponse().getStatus()).isEqualTo(404);

        MvcResult mismatch = ingest(KEY, "{\"deviceCode\":\"DEV-ING1\",\"sessionCode\":\"SES-OTHER\",\"readings\":[" + one + "]}");
        assertThat(body(mismatch)).contains("SESSION_MISMATCH");
        MvcResult match = ingest(KEY, "{\"deviceCode\":\"DEV-ING1\",\"sessionCode\":\"ses-test-ing1\",\"readings\":[" + one + "]}");
        assertThat(body(match)).contains("\"accepted\":1");

        jdbcTemplate.update("update monitoring_sessions set status='ENDED', ended_at=now(), ended_by=started_by");
        MvcResult ended = ingest(KEY, batch("DEV-ING1", reading("k2", Instant.now(), "96", "67")));
        assertThat(ended.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(ended)).contains("NO_ACTIVE_SESSION");
    }

    @Test
    void sameReadingKeyFromTwoDevicesIsTwoReadings() throws Exception {
        // a second device with its own patient + session
        var p2 = patientRepository.save(new com.somnisense.backend.patient.entity.Patient("PT-ING00002", "Two", null, null,
                null, com.somnisense.backend.common.domain.SourceType.SIMULATED, admin.getId()));
        var d2 = deviceRepository.save(new com.somnisense.backend.device.entity.Device("DEV-ING2",
                com.somnisense.backend.common.domain.SourceType.SIMULATED, null, null, admin.getId()));
        var a2 = assignmentRepository.save(new com.somnisense.backend.assignment.entity.DeviceAssignment(d2.getId(), p2.getId(), admin.getId()));
        sessionRepository.save(new com.somnisense.backend.session.entity.MonitoringSession("SES-TEST-ING2", p2.getId(),
                a2.getId(), admin.getId(), sessionStart));

        Instant t = Instant.now().minusSeconds(5);
        assertThat(body(ingest(KEY, batch("DEV-ING1", reading("000001", t, "96", "67"))))).contains("\"accepted\":1");
        assertThat(body(ingest(KEY, batch("DEV-ING2", reading("000001", t, "96", "67"))))).contains("\"accepted\":1");
    }

    @Test
    void batchShapeIsValidated() throws Exception {
        assertThat(ingest(KEY, "{\"deviceCode\":\"DEV-ING1\",\"readings\":[]}").getResponse().getStatus()).isEqualTo(400);
        assertThat(ingest(KEY, "{\"readings\":[" + reading("k", Instant.now(), "96", "67") + "]}").getResponse().getStatus())
                .isEqualTo(400);
        List<String> many = new ArrayList<>();
        Instant t = Instant.now().minusSeconds(5);
        for (int i = 0; i < 601; i++) {
            many.add(reading("k" + i, t, "96", "67"));
        }
        MvcResult tooMany = ingest(KEY, batch("DEV-ING1", many.toArray(String[]::new)));
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(tooMany)).contains("readings");
        assertThat(storedReadings()).isZero();
    }
}
