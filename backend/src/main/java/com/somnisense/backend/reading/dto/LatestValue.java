package com.somnisense.backend.reading.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.Instant;

/** A single metric's last known value and when it was measured: {"value":96.00,"measuredAt":"..."}. */
@JsonPropertyOrder({"value", "measuredAt"})
public class LatestValue {

    private final BigDecimal value;
    private final Instant measuredAt;

    public LatestValue(BigDecimal value, Instant measuredAt) {
        this.value = value;
        this.measuredAt = measuredAt;
    }

    public BigDecimal getValue() {
        return value;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }
}
