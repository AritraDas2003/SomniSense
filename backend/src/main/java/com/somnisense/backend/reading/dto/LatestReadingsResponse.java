package com.somnisense.backend.reading.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything the live monitoring cards need in one call (UI plan C44-C46, "Live update states"):
 *
 * - latest            : the most recent reading row (its spo2/heartRate may be null = dropout right now)
 * - lastSpo2          : the most recent NON-null SpO2 and when it was measured (show it, marked stale if old)
 * - lastHeartRate     : the same for heart rate
 * - connectivityStatus: CONNECTED / STALE / DISCONNECTED / UNKNOWN of the device, from lastSeenAt
 * - sourceType        : SIMULATED or PHYSICAL - show the SIMULATED badge
 * All three reading fields are null when no reading has arrived yet ("no readings yet", never 0% / 0 bpm).
 */
@JsonPropertyOrder({"sessionId", "sessionStatus", "sourceType", "connectivityStatus", "deviceLastSeenAt",
        "latest", "lastSpo2", "lastHeartRate"})
public class LatestReadingsResponse {

    private final UUID sessionId;
    private final String sessionStatus;
    private final String sourceType;
    private final String connectivityStatus;
    private final Instant deviceLastSeenAt;
    private final ReadingResponse latest;
    private final LatestValue lastSpo2;
    private final LatestValue lastHeartRate;

    public LatestReadingsResponse(UUID sessionId, String sessionStatus, String sourceType, String connectivityStatus,
                                  Instant deviceLastSeenAt, ReadingResponse latest, LatestValue lastSpo2,
                                  LatestValue lastHeartRate) {
        this.sessionId = sessionId;
        this.sessionStatus = sessionStatus;
        this.sourceType = sourceType;
        this.connectivityStatus = connectivityStatus;
        this.deviceLastSeenAt = deviceLastSeenAt;
        this.latest = latest;
        this.lastSpo2 = lastSpo2;
        this.lastHeartRate = lastHeartRate;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getSessionStatus() {
        return sessionStatus;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getConnectivityStatus() {
        return connectivityStatus;
    }

    public Instant getDeviceLastSeenAt() {
        return deviceLastSeenAt;
    }

    public ReadingResponse getLatest() {
        return latest;
    }

    public LatestValue getLastSpo2() {
        return lastSpo2;
    }

    public LatestValue getLastHeartRate() {
        return lastHeartRate;
    }
}
