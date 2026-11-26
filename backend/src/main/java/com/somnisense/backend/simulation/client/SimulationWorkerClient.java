package com.somnisense.backend.simulation.client;

import com.somnisense.backend.simulation.config.SimulationProperties;
import com.somnisense.backend.simulation.dto.WorkerControlResponse;
import com.somnisense.backend.simulation.dto.WorkerStartCommand;
import com.somnisense.backend.simulation.dto.WorkerStopCommand;
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
 * Internal HTTP control client for the standalone Python simulator-worker service.
 * Authenticates via X-Internal-Sim-Key and propagates X-Correlation-Id without logging secrets.
 */
@Component
public class SimulationWorkerClient {

    private static final Logger log = LoggerFactory.getLogger(SimulationWorkerClient.class);
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Sim-Key";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final int MIN_KEY_LENGTH = 32;

    private final SimulationProperties properties;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    public SimulationWorkerClient(SimulationProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    public WorkerControlResponse startWorker(WorkerStartCommand command) {
        return post("/internal/sim/start", command);
    }

    public WorkerControlResponse resumeWorker(WorkerStartCommand command) {
        return post("/internal/sim/resume", command);
    }

    public WorkerControlResponse pauseWorker(WorkerStopCommand command) {
        return post("/internal/sim/pause", command);
    }

    public WorkerControlResponse stopWorker(WorkerStopCommand command) {
        return post("/internal/sim/stop", command);
    }

    private WorkerControlResponse post(String path, Object body) {
        if (properties.getInternalKey().length() < MIN_KEY_LENGTH) {
            throw new SimulationWorkerException("Internal simulator worker key is not configured");
        }
        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        String payload;
        try {
            payload = jsonMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new SimulationWorkerException("Failed to serialize simulator worker command", ex);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(properties.getWorkerBaseUrl() + path))
                .timeout(properties.getReadTimeout())
                .header("Content-Type", "application/json")
                .header(INTERNAL_KEY_HEADER, properties.getInternalKey())
                .header(CORRELATION_HEADER, correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("Simulator worker {} returned HTTP {} [cid={}]", path, status, correlationId);
                throw new SimulationWorkerException("Simulator worker returned HTTP " + status);
            }
            if (response.body() == null || response.body().isBlank()) {
                return null;
            }
            return jsonMapper.readValue(response.body(), WorkerControlResponse.class);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SimulationWorkerException("Simulator worker request interrupted", ex);
        } catch (IOException ex) {
            throw new SimulationWorkerException("Simulator worker communication failed: " + ex.getClass().getSimpleName(), ex);
        }
    }
}
