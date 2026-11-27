package com.somnisense.backend.patient.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.assignment.dto.CurrentDeviceInfo;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.reading.dto.LatestReadingInfo;
import com.somnisense.backend.session.dto.ActiveSessionInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the patient LIST. Deliberately small: no date of birth, contact or medical data
 * ("avoid returning unnecessary sensitive information in list responses").
 * currentDevice (Stage 7) is the assigned device with its live connectivity, or null.
 * activeSession (Stage 8) is the running monitoring session, or null.
 * latestReading (Stage 9) is the newest reading of that active session, or null.
 */
@JsonPropertyOrder({"id", "patientCode", "fullName", "alias", "sourceType", "status", "currentDevice",
        "activeSession", "latestReading", "reviewStatus", "createdAt"})
public class PatientSummaryResponse {

    private final UUID id;
    private final String patientCode;
    private final String fullName;
    private final String alias;
    private final String sourceType;
    private final String status;
    private final CurrentDeviceInfo currentDevice;
    private final Instant createdAt;
    private final ActiveSessionInfo activeSession;
    private final LatestReadingInfo latestReading;
    private final String reviewStatus;

    public PatientSummaryResponse(Patient p, CurrentDeviceInfo currentDevice, ActiveSessionInfo activeSession,
                                  LatestReadingInfo latestReading) {
        this(p, currentDevice, activeSession, latestReading, "NONE");
    }

    public PatientSummaryResponse(Patient p, CurrentDeviceInfo currentDevice, ActiveSessionInfo activeSession,
                                  LatestReadingInfo latestReading, String reviewStatus) {
        this.activeSession = activeSession;
        this.latestReading = latestReading;
        this.reviewStatus = reviewStatus == null ? "NONE" : reviewStatus;
        this.id = p.getId();
        this.patientCode = p.getPatientCode();
        this.fullName = p.getFullName();
        this.alias = p.getAlias();
        this.sourceType = p.getSourceType().name();
        this.status = p.getStatus().name();
        this.currentDevice = currentDevice;
        this.createdAt = p.getCreatedAt();
    }

    public UUID getId() {
        return id;
    }

    public String getPatientCode() {
        return patientCode;
    }

    public String getFullName() {
        return fullName;
    }

    public String getAlias() {
        return alias;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getStatus() {
        return status;
    }

    public CurrentDeviceInfo getCurrentDevice() {
        return currentDevice;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public ActiveSessionInfo getActiveSession() {
        return activeSession;
    }

    public LatestReadingInfo getLatestReading() {
        return latestReading;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }
}
