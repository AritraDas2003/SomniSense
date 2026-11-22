package com.somnisense.backend.assignment.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.UUID;

/** "Which patient has this device right now?" - shown in the device inventory (UI plan C38). Null if unassigned. */
@JsonPropertyOrder({"assignmentId", "patientId", "patientCode", "assignedAt"})
public class CurrentPatientInfo {

    private final UUID assignmentId;
    private final UUID patientId;
    private final String patientCode;
    private final Instant assignedAt;

    public CurrentPatientInfo(UUID assignmentId, UUID patientId, String patientCode, Instant assignedAt) {
        this.assignmentId = assignmentId;
        this.patientId = patientId;
        this.patientCode = patientCode;
        this.assignedAt = assignedAt;
    }

    public UUID getAssignmentId() {
        return assignmentId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientCode() {
        return patientCode;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }
}
