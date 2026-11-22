package com.somnisense.backend.device.entity;

/**
 * Has the device been set up for use? (UI plan C34; decision 2026-11-21.)
 *
 * PENDING        - registered, setup not finished (PHYSICAL devices start here).
 * ENROLLED       - ready; only ENROLLED + active devices may be assigned to a patient (Stage 7).
 *                  SIMULATED devices start here because there is nothing physical to set up.
 * SETUP_REQUIRED - needs attention (e.g. re-pairing) before it can be used again.
 *
 * Separate from connectivity (is data arriving?) and from active/inactive (is the device in service?).
 */
public enum EnrollmentStatus {
    PENDING,
    ENROLLED,
    SETUP_REQUIRED
}
