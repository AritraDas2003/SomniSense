package com.somnisense.backend.prediction.dto;

/**
 * Outcome of PredictionService.recordPrediction: the persisted prediction plus whether it was newly created
 * (true) or matched an existing record for the same (sessionId, modelVersion, windowStart, windowEnd) (false).
 */
public final class RecordPredictionResult {

    private final PredictionResponse prediction;
    private final boolean created;

    public RecordPredictionResult(PredictionResponse prediction, boolean created) {
        this.prediction = prediction;
        this.created = created;
    }

    public PredictionResponse getPrediction() {
        return prediction;
    }

    public boolean isCreated() {
        return created;
    }
}
