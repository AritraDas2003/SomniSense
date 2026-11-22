package com.somnisense.backend.assignment.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.patient.entity.Patient;

import java.time.Instant;
import java.util.UUID;

/** One device-to-patient assignment, current or historical. "active" = not yet released. */
@JsonPropertyOrder({"id", "active", "deviceId", "deviceCode", "deviceType", "patientId", "patientCode",
        "patientFullName", "assignedBy", "assignedAt", "releasedBy", "releasedAt"})
public class AssignmentResponse {

    private final UUID id;
    private final boolean active;
    private final UUID deviceId;
    private final String deviceCode;
    private final String deviceType;
    private final UUID patientId;
    private final String patientCode;
    private final String patientFullName;
    private final UUID assignedBy;
    private final Instant assignedAt;
    private final UUID releasedBy;
    private final Instant releasedAt;

    public AssignmentResponse(DeviceAssignment a, Device device, Patient patient) {
        this.id = a.getId();
        this.active = a.isActive();
        this.deviceId = a.getDeviceId();
        this.deviceCode = device.getDeviceCode();
        this.deviceType = device.getDeviceType().name();
        this.patientId = a.getPatientId();
        this.patientCode = patient.getPatientCode();
        this.patientFullName = patient.getFullName();
        this.assignedBy = a.getAssignedBy();
        this.assignedAt = a.getAssignedAt();
        this.releasedBy = a.getReleasedBy();
        this.releasedAt = a.getReleasedAt();
    }

    public UUID getId() {
        return id;
    }

    public boolean isActive() {
        return active;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientCode() {
        return patientCode;
    }

    public String getPatientFullName() {
        return patientFullName;
    }

    public UUID getAssignedBy() {
        return assignedBy;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public UUID getReleasedBy() {
        return releasedBy;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
