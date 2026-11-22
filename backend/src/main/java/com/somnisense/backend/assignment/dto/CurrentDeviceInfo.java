package com.somnisense.backend.assignment.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.device.entity.ConnectivityStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * "Which device does this patient have right now?" - shown on patient list rows and the patient detail
 * (UI plan C15 device column, C23 freshness). Null when no device is assigned.
 */
@JsonPropertyOrder({"assignmentId", "deviceId", "deviceCode", "connectivityStatus", "lastSeenAt", "assignedAt"})
public class CurrentDeviceInfo {

    private final UUID assignmentId;
    private final UUID deviceId;
    private final String deviceCode;
    private final String connectivityStatus;
    private final Instant lastSeenAt;
    private final Instant assignedAt;

    public CurrentDeviceInfo(UUID assignmentId, UUID deviceId, String deviceCode, ConnectivityStatus connectivity,
                             Instant lastSeenAt, Instant assignedAt) {
        this.assignmentId = assignmentId;
        this.deviceId = deviceId;
        this.deviceCode = deviceCode;
        this.connectivityStatus = connectivity.name();
        this.lastSeenAt = lastSeenAt;
        this.assignedAt = assignedAt;
    }

    public UUID getAssignmentId() {
        return assignmentId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public String getConnectivityStatus() {
        return connectivityStatus;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }
}
