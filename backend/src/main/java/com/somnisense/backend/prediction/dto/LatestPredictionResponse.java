package com.somnisense.backend.prediction.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.UUID;

/**
 * Latest prediction snapshot for a monitoring session.
 *
 * When no prediction has been recorded for the session yet, hasPrediction is false and latest is null.
 * Missing predictions are never fabricated as NORMAL.
 */
@JsonPropertyOrder({"sessionId", "sessionStatus", "hasPrediction", "latest"})
public class LatestPredictionResponse {

    private final UUID sessionId;
    private final String sessionStatus;
    private final boolean hasPrediction;
    private final PredictionResponse latest;

    public LatestPredictionResponse(UUID sessionId, String sessionStatus, boolean hasPrediction,
                                    PredictionResponse latest) {
        this.sessionId = sessionId;
        this.sessionStatus = sessionStatus;
        this.hasPrediction = hasPrediction;
        this.latest = latest;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getSessionStatus() {
        return sessionStatus;
    }

    public boolean isHasPrediction() {
        return hasPrediction;
    }

    public PredictionResponse getLatest() {
        return latest;
    }
}
