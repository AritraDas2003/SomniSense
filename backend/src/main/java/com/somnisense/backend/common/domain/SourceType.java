package com.somnisense.backend.common.domain;

/**
 * Where a patient's / device's data comes from (approved SQL comments; decision 2026-11-21).
 * Used by patients.source_type now and devices.device_type in Stage 6, so both share one enum.
 * Simulated data must always stay distinguishable from physical data.
 */
public enum SourceType {
    PHYSICAL,
    SIMULATED
}
