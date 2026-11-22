package com.somnisense.backend.session.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.session.entity.MonitoringSession;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A monitoring session (UI plan C43 header, C53 session status).
 * sourceType = the device's type (PHYSICAL/SIMULATED), so simulated sessions are always recognisable.
 * durationSeconds = started -> ended, or started -> now while ACTIVE.
 */
@JsonPropertyOrder({"id", "sessionCode", "status", "sourceType", "patientId", "patientCode", "deviceAssignmentId",
        "deviceId", "deviceCode", "startedBy", "startedAt", "endedBy", "endedAt", "durationSeconds"})
public class SessionResponse {

    private final UUID id;
    private final String sessionCode;
    private final String status;
    private final String sourceType;
    private final UUID patientId;
    private final String patientCode;
    private final UUID deviceAssignmentId;
    private final UUID deviceId;
    private final String deviceCode;
    private final UUID startedBy;
    private final Instant startedAt;
    private final UUID endedBy;
    private final Instant endedAt;
    private final long durationSeconds;

    public SessionResponse(MonitoringSession s, Patient patient, Device device, Instant now) {
        this.id = s.getId();
        this.sessionCode = s.getSessionCode();
        this.status = s.getStatus().name();
        this.sourceType = device.getDeviceType().name();
        this.patientId = s.getPatientId();
        this.patientCode = patient.getPatientCode();
        this.deviceAssignmentId = s.getDeviceAssignmentId();
        this.deviceId = device.getId();
        this.deviceCode = device.getDeviceCode();
        this.startedBy = s.getStartedBy();
        this.startedAt = s.getStartedAt();
        this.endedBy = s.getEndedBy();
        this.endedAt = s.getEndedAt();
        Instant end = s.getEndedAt() != null ? s.getEndedAt() : now;
        this.durationSeconds = Math.max(0, Duration.between(s.getStartedAt(), end).getSeconds());
    }

    public UUID getId() {
        return id;
    }

    public String getSessionCode() {
        return sessionCode;
    }

    public String getStatus() {
        return status;
    }

    public String getSourceType() {
        return sourceType;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientCode() {
        return patientCode;
    }

    public UUID getDeviceAssignmentId() {
        return deviceAssignmentId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public UUID getStartedBy() {
        return startedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public UUID getEndedBy() {
        return endedBy;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }
}
