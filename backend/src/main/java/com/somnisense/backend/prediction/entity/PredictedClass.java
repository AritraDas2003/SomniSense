package com.somnisense.backend.prediction.entity;

/**
 * Binary window classification emitted by the frozen Model C pipeline.
 * Nullable in the database when a window is REJECTED or encounters an ERROR.
 */
public enum PredictedClass {
    APNEA_EVENT,
    NORMAL
}
