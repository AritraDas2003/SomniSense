package com.somnisense.backend.prediction.dto;

import com.somnisense.backend.prediction.entity.InputQualityStatus;
import com.somnisense.backend.prediction.entity.PredictedClass;
import com.somnisense.backend.prediction.entity.PredictionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Internal service command for persisting a window-level prediction produced by Model C orchestration.
 * Not exposed as a public REST request body in Stage 2.
 */
public final class RecordPredictionCommand {

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

    public RecordPredictionCommand(UUID sessionId, String modelVersion, Instant windowStart, Instant windowEnd,
                                   PredictedClass predictedClass, BigDecimal confidenceScore,
                                   PredictionStatus predictionStatus, InputQualityStatus inputQualityStatus,
                                   Instant processedAt, Integer processingTimeMs, String errorMessage) {
        this.sessionId = sessionId;
        this.modelVersion = modelVersion;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.predictedClass = predictedClass;
        this.confidenceScore = confidenceScore;
        this.predictionStatus = predictionStatus;
        this.inputQualityStatus = inputQualityStatus;
        this.processedAt = processedAt;
        this.processingTimeMs = processingTimeMs;
        this.errorMessage = errorMessage;
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
