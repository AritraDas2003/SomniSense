package com.somnisense.backend.prediction.entity;

/**
 * Lifecycle state of a scheduled window evaluation job in "inference_window_jobs".
 */
public enum InferenceJobStatus {
    RETRY_WAIT,
    SUCCEEDED,
    FAILED_EXHAUSTED,
    CANCELLED
}
