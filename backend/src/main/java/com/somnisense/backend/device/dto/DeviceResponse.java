package com.somnisense.backend.device.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.assignment.dto.CurrentPatientInfo;
import com.somnisense.backend.device.entity.ConnectivityStatus;
import com.somnisense.backend.device.entity.Device;

import java.time.Instant;
import java.util.UUID;

/**
 * A device as shown in the inventory table and detail panel (UI plan C38/C41).
 * connectivityStatus is computed from lastSeenAt at the moment of the request (ConnectivityPolicy).
 * currentAssignment (Stage 7) is null when the device is not assigned. No secrets are ever included.
 */
@JsonPropertyOrder({"id", "deviceCode", "deviceType", "modelName", "serialNumber", "active", "enrollmentStatus",
        "connectivityStatus", "lastSeenAt", "currentAssignment", "registeredBy", "createdAt", "updatedAt"})
public class DeviceResponse {

    private final UUID id;
    private final String deviceCode;
    private final String deviceType;
    private final String modelName;
    private final String serialNumber;
    private final boolean active;
    private final String enrollmentStatus;
    private final String connectivityStatus;
    private final Instant lastSeenAt;
    private final UUID registeredBy;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final CurrentPatientInfo currentAssignment;

    /** currentAssignment: the patient this device is assigned to right now, or null. */
    public DeviceResponse(Device d, ConnectivityStatus connectivity, CurrentPatientInfo currentAssignment) {
        this.currentAssignment = currentAssignment;
        this.id = d.getId();
        this.deviceCode = d.getDeviceCode();
        this.deviceType = d.getDeviceType().name();
        this.modelName = d.getModelName();
        this.serialNumber = d.getSerialNumber();
        this.active = d.isActive();
        this.enrollmentStatus = d.getEnrollmentStatus().name();
        this.connectivityStatus = connectivity.name();
        this.lastSeenAt = d.getLastSeenAt();
        this.registeredBy = d.getRegisteredBy();
        this.createdAt = d.getCreatedAt();
        this.updatedAt = d.getUpdatedAt();
    }

    public UUID getId() {
        return id;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public String getModelName() {
        return modelName;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public boolean isActive() {
        return active;
    }

    public String getEnrollmentStatus() {
        return enrollmentStatus;
    }

    public String getConnectivityStatus() {
        return connectivityStatus;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public UUID getRegisteredBy() {
        return registeredBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public CurrentPatientInfo getCurrentAssignment() {
        return currentAssignment;
    }
}
