package com.somnisense.backend.simulation.dto;

import java.util.UUID;

/** Response returned by Python simulator-worker control endpoints. */
public record WorkerControlResponse(
        UUID simulationRunId,
        UUID sessionId,
        String workerInstanceId,
        String status,
        Long lastSubmittedStep
) {
}
