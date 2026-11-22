package com.somnisense.backend.reading.entity;

import com.somnisense.backend.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One SpO2 / heart-rate observation (approved "sensor_readings" table).
 *
 * - spo2 / heartRate / signalQuality are NULL when the value is missing (e.g. sensor dropout).
 *   They are never turned into 0.
 * - measuredAt = when the device measured it (device clock); receivedAt = when the server got it (server clock).
 *   Both are kept separately.
 * - BigDecimal matches PostgreSQL numeric(5,2)/(6,2) exactly (no floating-point rounding surprises).
 * - @Immutable: readings are raw data and are never edited.
 *
 * Bulk ingestion inserts rows with plain SQL (IngestionService) for "skip duplicates" semantics; this entity is
 * used for reading them back.
 */
@Entity
@Immutable
@Table(name = "sensor_readings")
public class SensorReading extends AssignedIdEntity {

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(name = "reading_key", length = 100, updatable = false)
    private String readingKey;

    @Column(name = "spo2", precision = 5, scale = 2, updatable = false)
    private BigDecimal spo2;

    @Column(name = "heart_rate", precision = 6, scale = 2, updatable = false)
    private BigDecimal heartRate;

    @Column(name = "signal_quality", precision = 5, scale = 2, updatable = false)
    private BigDecimal signalQuality;

    @Column(name = "measured_at", nullable = false, updatable = false)
    private Instant measuredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected SensorReading() {
    }

    public SensorReading(UUID sessionId, UUID deviceId, String readingKey, BigDecimal spo2, BigDecimal heartRate,
                         BigDecimal signalQuality, Instant measuredAt, Instant receivedAt) {
        super(UUID.randomUUID());
        this.sessionId = Objects.requireNonNull(sessionId);
        this.deviceId = Objects.requireNonNull(deviceId);
        this.readingKey = readingKey;
        this.spo2 = spo2;
        this.heartRate = heartRate;
        this.signalQuality = signalQuality;
        this.measuredAt = Objects.requireNonNull(measuredAt);
        this.receivedAt = Objects.requireNonNull(receivedAt);
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getReadingKey() {
        return readingKey;
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

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
