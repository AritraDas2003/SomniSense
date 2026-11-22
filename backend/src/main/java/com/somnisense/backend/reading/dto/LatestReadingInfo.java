package com.somnisense.backend.reading.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.reading.entity.SensorReading;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The newest reading of a patient's ACTIVE session, for the dashboard columns "latest SpO2/HR, last reading time"
 * (UI plan C15/C23). spo2/heartRate may be null (dropout) - show "no value", never 0.
 */
@JsonPropertyOrder({"spo2", "heartRate", "measuredAt"})
public class LatestReadingInfo {

    private final BigDecimal spo2;
    private final BigDecimal heartRate;
    private final Instant measuredAt;

    public LatestReadingInfo(SensorReading r) {
        this.spo2 = r.getSpo2();
        this.heartRate = r.getHeartRate();
        this.measuredAt = r.getMeasuredAt();
    }

    public BigDecimal getSpo2() {
        return spo2;
    }

    public BigDecimal getHeartRate() {
        return heartRate;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }
}
