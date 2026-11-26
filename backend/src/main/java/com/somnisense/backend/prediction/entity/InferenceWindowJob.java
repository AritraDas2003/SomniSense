package com.somnisense.backend.prediction.entity;

import com.somnisense.backend.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable retry/state record for scheduled Model C window evaluations (V6 "inference_window_jobs").
 */
@Entity
@Table(name = "inference_window_jobs")
@DynamicUpdate
public class InferenceWindowJob extends AssignedIdEntity {

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "model_version", nullable = false, length = 50, updatable = false)
    private String modelVersion;

    @Column(name = "window_start", nullable = false, updatable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false, updatable = false)
    private Instant windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InferenceJobStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InferenceWindowJob() {
    }

    public InferenceWindowJob(UUID sessionId, String modelVersion, Instant windowStart, Instant windowEnd,
                              InferenceJobStatus status, int attemptCount, Instant nextRetryAt,
                              String lastError, Instant now) {
        super(UUID.randomUUID());
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
        this.windowStart = Objects.requireNonNull(windowStart, "windowStart");
        this.windowEnd = Objects.requireNonNull(windowEnd, "windowEnd");
        this.status = Objects.requireNonNull(status, "status");
        this.attemptCount = attemptCount;
        this.nextRetryAt = nextRetryAt;
        this.lastError = lastError;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void markSucceeded(int totalAttempts, Instant now) {
        this.status = InferenceJobStatus.SUCCEEDED;
        this.attemptCount = Math.max(this.attemptCount, totalAttempts);
        this.nextRetryAt = null;
        this.lastError = null;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public void scheduleRetry(int newAttemptCount, Instant nextRetryAt, String errorMessage, Instant now) {
        this.status = InferenceJobStatus.RETRY_WAIT;
        this.attemptCount = newAttemptCount;
        this.nextRetryAt = Objects.requireNonNull(nextRetryAt, "nextRetryAt");
        this.lastError = errorMessage;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markExhausted(int totalAttempts, String errorMessage, Instant now) {
        this.status = InferenceJobStatus.FAILED_EXHAUSTED;
        this.attemptCount = totalAttempts;
        this.nextRetryAt = null;
        this.lastError = errorMessage;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markCancelled(Instant now) {
        this.status = InferenceJobStatus.CANCELLED;
        this.nextRetryAt = null;
        this.updatedAt = Objects.requireNonNull(now, "now");
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

    public InferenceJobStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
