package com.somnisense.backend.simulation.dto;

import java.util.UUID;

/** Payload sent to Python simulator-worker to pause or stop a running simulation. */
public record WorkerStopCommand(
        UUID sessionId,
        UUID simulationRunId,
        Long workerGeneration
) {
}
