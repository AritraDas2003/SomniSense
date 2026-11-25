package com.somnisense.backend.prediction.entity;

import com.somnisense.backend.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One window-level model prediction or quality/error outcome (approved "predictions" table).
 *
 * - Immutable append-only record: predictions are never mutated in place.
 * - predictedClass and confidenceScore stay NULL for REJECTED or ERROR outcomes (never fabricated).
 * - BigDecimal(7,6) matches PostgreSQL numeric(7,6) for probabilities in [0.000000, 1.000000].
 */
@Entity
@Immutable
@Table(name = "predictions")
public class Prediction extends AssignedIdEntity {

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "model_version", nullable = false, length = 50, updatable = false)
    private String modelVersion;

    @Column(name = "window_start", nullable = false, updatable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false, updatable = false)
    private Instant windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "predicted_class", length = 40, updatable = false)
    private PredictedClass predictedClass;

    @Column(name = "confidence_score", precision = 7, scale = 6, updatable = false)
    private BigDecimal confidenceScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "prediction_status", nullable = false, length = 30, updatable = false)
    private PredictionStatus predictionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_quality_status", length = 30, updatable = false)
    private InputQualityStatus inputQualityStatus;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    @Column(name = "processing_time_ms", updatable = false)
    private Integer processingTimeMs;

    @Column(name = "error_message", updatable = false)
    private String errorMessage;

    protected Prediction() {
    }

    public Prediction(UUID sessionId, String modelVersion, Instant windowStart, Instant windowEnd,
                      PredictedClass predictedClass, BigDecimal confidenceScore, PredictionStatus predictionStatus,
                      InputQualityStatus inputQualityStatus, Instant processedAt, Integer processingTimeMs,
                      String errorMessage) {
        super(UUID.randomUUID());
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
        this.windowStart = Objects.requireNonNull(windowStart, "windowStart");
        this.windowEnd = Objects.requireNonNull(windowEnd, "windowEnd");
        this.predictedClass = predictedClass;
        this.confidenceScore = confidenceScore;
        this.predictionStatus = Objects.requireNonNull(predictionStatus, "predictionStatus");
        this.inputQualityStatus = inputQualityStatus;
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
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
