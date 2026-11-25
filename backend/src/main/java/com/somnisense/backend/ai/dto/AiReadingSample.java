package com.somnisense.backend.ai.dto;

import com.somnisense.backend.reading.entity.SensorReading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Single sensor reading transmitted in deterministic order to the internal AI sidecar.
 */
public record AiReadingSample(
        UUID id,
        Instant measuredAt,
        Instant receivedAt,
        BigDecimal spo2,
        BigDecimal heartRate
) {
    public static AiReadingSample from(SensorReading reading) {
        return new AiReadingSample(
                reading.getId(),
                reading.getMeasuredAt(),
                reading.getReceivedAt(),
                reading.getSpo2(),
                reading.getHeartRate()
        );
    }
}
