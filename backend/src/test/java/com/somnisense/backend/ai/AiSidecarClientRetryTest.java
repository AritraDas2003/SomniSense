package com.somnisense.backend.ai;

import com.somnisense.backend.ai.client.AiSidecarClient;
import com.somnisense.backend.ai.client.AiSidecarException;
import com.somnisense.backend.ai.config.AiProperties;
import com.somnisense.backend.ai.dto.AiEvaluateWindowRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSidecarClientRetryTest {

    private static final String TEST_KEY = "test-internal-ai-key-0123456789abcdef-0123456789";

    private HttpServer server;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicLong delayMs = new AtomicLong(0L);
    private AiSidecarClient client;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/ai/evaluate-window", exchange -> {
            long sleep = delayMs.get();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
        AiProperties props = new AiProperties("http://127.0.0.1:" + port, TEST_KEY,
                Duration.ofMillis(300), Duration.ofMillis(250), "phase7_model_c:1.0.0");
        client = new AiSidecarClient(props, jsonMapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void classifiesRetryableHttpAndNetworkFailures() throws Exception {
        for (int code : List.of(429, 500, 502, 503)) {
            status.set(code);
            assertThatThrownBy(() -> client.evaluateWindow(sampleRequest()))
                    .isInstanceOf(AiSidecarException.class)
                    .satisfies(ex -> {
                        AiSidecarException sidecarEx = (AiSidecarException) ex;
                        assertThat(sidecarEx.getHttpStatus()).isEqualTo(code);
                        assertThat(sidecarEx.isRetryable()).isTrue();
                    });
        }

        // Timeout is retryable
        status.set(200);
        delayMs.set(700L);
        assertThatThrownBy(() -> client.evaluateWindow(sampleRequest()))
                .isInstanceOf(AiSidecarException.class)
                .satisfies(ex -> assertThat(((AiSidecarException) ex).isRetryable()).isTrue());

        // Connection refused is retryable
        delayMs.set(0L);
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            closedPort = ss.getLocalPort();
        }
        AiSidecarClient downClient = new AiSidecarClient(
                new AiProperties("http://127.0.0.1:" + closedPort, TEST_KEY,
                        Duration.ofMillis(300), Duration.ofMillis(300), "phase7_model_c:1.0.0"),
                jsonMapper);
        assertThatThrownBy(() -> downClient.evaluateWindow(sampleRequest()))
                .isInstanceOf(AiSidecarException.class)
                .satisfies(ex -> {
                    AiSidecarException sidecarEx = (AiSidecarException) ex;
                    assertThat(sidecarEx.getHttpStatus()).isNull();
                    assertThat(sidecarEx.isRetryable()).isTrue();
                });
    }

    @Test
    void classifiesClientErrorsInvalidKeyAndMalformedJsonAsNonRetryable() {
        for (int code : List.of(400, 401, 403, 422)) {
            status.set(code);
            assertThatThrownBy(() -> client.evaluateWindow(sampleRequest()))
                    .isInstanceOf(AiSidecarException.class)
                    .satisfies(ex -> {
                        AiSidecarException sidecarEx = (AiSidecarException) ex;
                        assertThat(sidecarEx.getHttpStatus()).isEqualTo(code);
                        assertThat(sidecarEx.isRetryable()).isFalse();
                    });
        }

        status.set(200);
        body.set("not-json");
        assertThatThrownBy(() -> client.evaluateWindow(sampleRequest()))
                .isInstanceOf(AiSidecarException.class)
                .satisfies(ex -> assertThat(((AiSidecarException) ex).isRetryable()).isFalse());

        AiSidecarClient badKeyClient = new AiSidecarClient(
                new AiProperties("http://127.0.0.1:" + port, "too-short",
                        Duration.ofMillis(300), Duration.ofMillis(300), "phase7_model_c:1.0.0"),
                jsonMapper);
        assertThatThrownBy(() -> badKeyClient.evaluateWindow(sampleRequest()))
                .isInstanceOf(AiSidecarException.class)
                .satisfies(ex -> assertThat(((AiSidecarException) ex).isRetryable()).isFalse());
    }

    private static AiEvaluateWindowRequest sampleRequest() {
        Instant t1 = Instant.parse("2026-11-26T00:01:00Z");
        return new AiEvaluateWindowRequest(
                UUID.randomUUID(),
                t1.minusSeconds(60),
                t1,
                t1.minusSeconds(360),
                List.of()
        );
    }
}
