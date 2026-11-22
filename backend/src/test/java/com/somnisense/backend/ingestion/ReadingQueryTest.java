package com.somnisense.backend.ingestion;

import com.jayway.jsonpath.JsonPath;
import com.somnisense.backend.support.LoggedInClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 9: reading history and latest values for staff. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReadingQueryTest extends IngestionTestBase {

    private LoggedInClient op;
    private Instant t0;

    @BeforeEach
    void login() throws Exception {
        op = LoggedInClient.login(mockMvc, "operator1", PW);
        // PostgreSQL stores timestamps to the microsecond; Windows clocks give 100 ns. Use whole milliseconds so the
        // values we compare against are exactly what the database returns.
        t0 = Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    private String url() {
        return "/api/v1/sessions/" + session.getId() + "/readings";
    }

    @Test
    void historyIsOrderedPagedAndWindowed() throws Exception {
        // sent out of order on purpose
        ingest(KEY, batch("DEV-ING1", reading("c", t0.plusSeconds(2), "94", "70"),
                reading("a", t0, "96", "67"), reading("b", t0.plusSeconds(1), "95", "68")));

        String json = mockMvc.perform(op.with(get(url())).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn().getResponse().getContentAsString();
        List<String> keys = JsonPath.read(json, "$.content[*].readingKey");
        assertThat(keys).containsExactly("a", "b");   // oldest first

        mockMvc.perform(op.with(get(url())).param("from", t0.plusSeconds(1).toString()).param("to", t0.plusSeconds(2).toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].readingKey").value("b"));

        mockMvc.perform(op.with(get(url())).param("size", "1001")).andExpect(status().isBadRequest());
        mockMvc.perform(op.with(get(url())).param("from", t0.toString()).param("to", t0.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(op.with(get("/api/v1/sessions/" + UUID.randomUUID() + "/readings"))).andExpect(status().isNotFound());
        mockMvc.perform(get(url())).andExpect(status().isUnauthorized());
    }

    @Test
    void latestWithNoReadingsIsEmptyNotZero() throws Exception {
        mockMvc.perform(op.with(get(url() + "/latest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latest").value(nullValue()))
                .andExpect(jsonPath("$.lastSpo2").value(nullValue()))
                .andExpect(jsonPath("$.lastHeartRate").value(nullValue()))
                .andExpect(jsonPath("$.connectivityStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.sourceType").value("SIMULATED"));
    }

    @Test
    void latestShowsTheDropoutAndTheLastKnownValuesSeparately() throws Exception {
        ingest(KEY, batch("DEV-ING1",
                reading("a", t0, "96", "67"),
                reading("b", t0.plusSeconds(1), "93", "null"),
                reading("c", t0.plusSeconds(2), "null", "null")));   // newest = dropout

        mockMvc.perform(op.with(get(url() + "/latest")))
                .andExpect(jsonPath("$.latest.readingKey").value("c"))
                .andExpect(jsonPath("$.latest.spo2").value(nullValue()))
                .andExpect(jsonPath("$.lastSpo2.value").value(93.0))
                .andExpect(jsonPath("$.lastSpo2.measuredAt").value(t0.plusSeconds(1).toString()))
                .andExpect(jsonPath("$.lastHeartRate.value").value(67.0))
                .andExpect(jsonPath("$.connectivityStatus").value("CONNECTED"))
                .andExpect(jsonPath("$.sessionStatus").value("ACTIVE"));
    }

    @Test
    void patientListAndDetailShowTheLatestReading() throws Exception {
        ingest(KEY, batch("DEV-ING1", reading("a", t0, "96", "67"), reading("b", t0.plusSeconds(1), "95", "66")));
        mockMvc.perform(op.with(get("/api/v1/patients")).param("q", "PT-ING00001"))
                .andExpect(jsonPath("$.content[0].latestReading.spo2").value(95.0))
                .andExpect(jsonPath("$.content[0].latestReading.heartRate").value(66.0))
                .andExpect(jsonPath("$.content[0].currentDevice.connectivityStatus").value("CONNECTED"));
        mockMvc.perform(op.with(get("/api/v1/patients/" + patient.getId())))
                .andExpect(jsonPath("$.latestReading.measuredAt").value(t0.plusSeconds(1).toString()));
    }

    @Test
    void readingsStayAvailableAfterTheSessionEnds() throws Exception {
        ingest(KEY, batch("DEV-ING1", reading("a", t0, "96", "67")));
        mockMvc.perform(op.with(post("/api/v1/sessions/" + session.getId() + "/end"))).andExpect(status().isOk());
        mockMvc.perform(op.with(get(url()))).andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(op.with(get("/api/v1/patients/" + patient.getId())))
                .andExpect(jsonPath("$.latestReading").value(nullValue()));   // no ACTIVE session any more
    }
}
