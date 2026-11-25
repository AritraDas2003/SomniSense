package com.somnisense.backend.prediction.entity;

/**
 * Outcome status of an inference window persisted in "predictions".
 */
public enum PredictionStatus {
    COMPLETED,
    NEEDS_REVIEW,
    REJECTED,
    ERROR
}
