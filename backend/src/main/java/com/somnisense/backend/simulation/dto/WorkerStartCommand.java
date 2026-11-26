package com.somnisense.backend.simulation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Payload dispatched from Spring Boot to Python simulator-worker on start or resume. */
public record WorkerStartCommand(
        UUID simulationRunId,
        UUID sessionId,
        String sessionCode,
        String deviceCode,
        String scenarioId,
        long seed,
        BigDecimal cadenceHz,
        BigDecimal playbackSpeed,
        String qualityPreset,
        long workerGeneration,
        long startStep,
        Instant sessionStartedAt,
        long leaseDurationSeconds,
        String ingestionBaseUrl
) {
}
