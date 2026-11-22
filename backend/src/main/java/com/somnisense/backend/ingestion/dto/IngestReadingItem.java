package com.somnisense.backend.ingestion.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One reading inside an ingestion batch.
 * {"readingKey":"DEV-H2FP2ABH-000123","measuredAt":"2026-11-21T14:05:00Z","spo2":96.0,"heartRate":67.0,"signalQuality":null}
 *
 * No @NotNull/@Min annotations here on purpose: each reading is checked separately by IngestionService, so one bad
 * reading is reported and skipped instead of rejecting the whole batch (decision 2026-11-21: partial acceptance).
 * null spo2 / heartRate = value missing (e.g. dropout) and is stored as NULL, never as 0.
 */
public class IngestReadingItem {

    private String readingKey;
    private Instant measuredAt;
    private BigDecimal spo2;
    private BigDecimal heartRate;
    private BigDecimal signalQuality;

    public String getReadingKey() {
        return readingKey;
    }

    public void setReadingKey(String readingKey) {
        this.readingKey = readingKey;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public void setMeasuredAt(Instant measuredAt) {
        this.measuredAt = measuredAt;
    }

    public BigDecimal getSpo2() {
        return spo2;
    }

    public void setSpo2(BigDecimal spo2) {
        this.spo2 = spo2;
    }

    public BigDecimal getHeartRate() {
        return heartRate;
    }

    public void setHeartRate(BigDecimal heartRate) {
        this.heartRate = heartRate;
    }

    public BigDecimal getSignalQuality() {
        return signalQuality;
    }

    public void setSignalQuality(BigDecimal signalQuality) {
        this.signalQuality = signalQuality;
    }
}
