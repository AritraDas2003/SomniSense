package com.somnisense.backend.simulation.dto;

import com.somnisense.backend.simulation.entity.SimulationRun;
import com.somnisense.backend.simulation.entity.SimulationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Safe external view of a session's simulator run metadata and fencing checkpoint. */
public record SimulationResponse(
        UUID id,
        UUID sessionId,
        String scenarioId,
        long seed,
        BigDecimal cadenceHz,
        BigDecimal playbackSpeed,
        String qualityPreset,
        SimulationStatus status,
        long lastStep,
        long workerGeneration,
        Instant leaseExpiresAt,
        String workerInstanceId,
        Instant startedAt,
        Instant updatedAt
) {
    public SimulationResponse(SimulationRun run) {
        this(
                run.getId(),
                run.getSessionId(),
                run.getScenarioId(),
                run.getSeed(),
                run.getCadenceHz(),
                run.getPlaybackSpeed(),
                run.getQualityPreset(),
                run.getStatus(),
                run.getLastStep(),
                run.getWorkerGeneration(),
                run.getLeaseExpiresAt(),
                run.getWorkerInstanceId(),
                run.getStartedAt(),
                run.getUpdatedAt()
        );
    }
}
