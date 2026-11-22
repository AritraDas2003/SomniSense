package com.somnisense.backend.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Checks that every response carries an X-Correlation-Id and that unsafe incoming IDs are replaced. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorrelationIdFilterTest {

    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generatesIdWhenNoneSent() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, matchesPattern(UUID_PATTERN)));
    }

    @Test
    void reusesSafeIncomingId() throws Exception {
        mockMvc.perform(get("/api/v1/health").header(CorrelationIdFilter.HEADER_NAME, "postman-test-123"))
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "postman-test-123"));
    }

    @Test
    void replacesUnsafeIncomingId() throws Exception {
        mockMvc.perform(get("/api/v1/health").header(CorrelationIdFilter.HEADER_NAME, "bad id\r\nINJECTED"))
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, matchesPattern(UUID_PATTERN)));
    }

    @Test
    void errorBodyAndHeaderShareTheSameId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/anything-protected")
                        .header(CorrelationIdFilter.HEADER_NAME, "trace-me-42"))
                .andExpect(jsonPath("$.correlationId").value("trace-me-42"))
                .andReturn();
        assertThat(result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("trace-me-42");
    }
}
