package com.somnisense.backend.ai.client;

import com.somnisense.backend.ai.config.AiProperties;
import com.somnisense.backend.ai.dto.AiEvaluateWindowRequest;
import com.somnisense.backend.ai.dto.AiEvaluateWindowResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * HTTP client for the internal Python FastAPI Model C sidecar.
 * Sends X-Internal-AI-Key and X-Correlation-Id, enforces explicit connect/read timeouts,
 * and never logs secret keys.
 */
@Component
public class AiSidecarClient {

    private static final Logger log = LoggerFactory.getLogger(AiSidecarClient.class);
    private static final String INTERNAL_KEY_HEADER = "X-Internal-AI-Key";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final int MIN_KEY_LENGTH = 32;

    private final AiProperties properties;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    public AiSidecarClient(AiProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    public AiEvaluateWindowResponse evaluateWindow(AiEvaluateWindowRequest request) {
        if (properties.getApiKey().length() < MIN_KEY_LENGTH) {
            throw new AiSidecarException("Internal AI sidecar API key is not configured");
        }

        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        String payload;
        try {
            payload = jsonMapper.writeValueAsString(request);
        } catch (Exception ex) {
            throw new AiSidecarException("Failed to serialize AI evaluation request", ex);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + "/internal/ai/evaluate-window"))
                .timeout(properties.getReadTimeout())
                .header("Content-Type", "application/json")
                .header(INTERNAL_KEY_HEADER, properties.getApiKey())
                .header(CORRELATION_HEADER, correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("AI sidecar returned HTTP {} for session {} [cid={}]", status, request.sessionId(), correlationId);
                throw new AiSidecarException("AI sidecar returned HTTP " + status);
            }
            AiEvaluateWindowResponse parsed = jsonMapper.readValue(response.body(), AiEvaluateWindowResponse.class);
            if (parsed == null || parsed.predictionStatus() == null) {
                throw new AiSidecarException("AI sidecar returned incomplete evaluation payload");
            }
            return parsed;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AiSidecarException("AI sidecar request interrupted", ex);
        } catch (IOException ex) {
            throw new AiSidecarException("AI sidecar communication failed: " + ex.getClass().getSimpleName(), ex);
        }
    }
}
