package com.somnisense.backend.reading.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.reading.entity.SensorReading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One reading as returned to the frontend.
 * spo2 / heartRate are null when the value was missing - draw a GAP in the chart, never a 0 (UI plan C51).
 */
@JsonPropertyOrder({"id", "readingKey", "measuredAt", "receivedAt", "spo2", "heartRate", "signalQuality"})
public class ReadingResponse {

    private final UUID id;
    private final String readingKey;
    private final Instant measuredAt;
    private final Instant receivedAt;
    private final BigDecimal spo2;
    private final BigDecimal heartRate;
    private final BigDecimal signalQuality;

    public ReadingResponse(SensorReading r) {
        this.id = r.getId();
        this.readingKey = r.getReadingKey();
        this.measuredAt = r.getMeasuredAt();
        this.receivedAt = r.getReceivedAt();
        this.spo2 = r.getSpo2();
        this.heartRate = r.getHeartRate();
        this.signalQuality = r.getSignalQuality();
    }

    public UUID getId() {
        return id;
    }

    public String getReadingKey() {
        return readingKey;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public BigDecimal getSpo2() {
        return spo2;
    }

    public BigDecimal getHeartRate() {
        return heartRate;
    }

    public BigDecimal getSignalQuality() {
        return signalQuality;
    }
}
