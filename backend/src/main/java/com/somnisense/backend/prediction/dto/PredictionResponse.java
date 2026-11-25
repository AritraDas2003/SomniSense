package com.somnisense.backend.prediction.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.prediction.entity.InputQualityStatus;
import com.somnisense.backend.prediction.entity.PredictedClass;
import com.somnisense.backend.prediction.entity.Prediction;
import com.somnisense.backend.prediction.entity.PredictionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One persisted window-level prediction or quality/error record returned to staff clients.
 */
@JsonPropertyOrder({
        "id", "sessionId", "modelVersion", "windowStart", "windowEnd",
        "predictedClass", "confidenceScore", "predictionStatus", "inputQualityStatus",
        "processedAt", "processingTimeMs", "errorMessage"
})
public class PredictionResponse {

    private final UUID id;
    private final UUID sessionId;
    private final String modelVersion;
    private final Instant windowStart;
    private final Instant windowEnd;
    private final PredictedClass predictedClass;
    private final BigDecimal confidenceScore;
    private final PredictionStatus predictionStatus;
    private final InputQualityStatus inputQualityStatus;
    private final Instant processedAt;
    private final Integer processingTimeMs;
    private final String errorMessage;

    public PredictionResponse(Prediction p) {
        this.id = p.getId();
        this.sessionId = p.getSessionId();
        this.modelVersion = p.getModelVersion();
        this.windowStart = p.getWindowStart();
        this.windowEnd = p.getWindowEnd();
        this.predictedClass = p.getPredictedClass();
        this.confidenceScore = p.getConfidenceScore();
        this.predictionStatus = p.getPredictionStatus();
        this.inputQualityStatus = p.getInputQualityStatus();
        this.processedAt = p.getProcessedAt();
        this.processingTimeMs = p.getProcessingTimeMs();
        this.errorMessage = p.getErrorMessage();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public PredictedClass getPredictedClass() {
        return predictedClass;
    }

    public BigDecimal getConfidenceScore() {
        return confidenceScore;
    }

    public PredictionStatus getPredictionStatus() {
        return predictionStatus;
    }

    public InputQualityStatus getInputQualityStatus() {
        return inputQualityStatus;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Integer getProcessingTimeMs() {
        return processingTimeMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
