package com.somnisense.backend.prediction.entity;

/**
 * Quality classification of the input reading window prior to or during inference.
 */
public enum InputQualityStatus {
    VALID,
    DEGRADED,
    REJECTED
}
