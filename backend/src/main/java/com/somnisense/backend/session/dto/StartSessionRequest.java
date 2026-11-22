package com.somnisense.backend.session.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of POST /api/v1/sessions: {"patientId":"...", "deviceAssignmentId":"..." (optional)}
 *
 * The backend uses the patient's CURRENT active assignment. If deviceAssignmentId is sent and it is not that
 * assignment (e.g. the screen was stale), the request is refused with 409 ASSIGNMENT_MISMATCH.
 * There is no startedBy/startedAt field: who and when always come from the server.
 */
public class StartSessionRequest {

    @NotNull
    private UUID patientId;

    private UUID deviceAssignmentId;

    public UUID getPatientId() {
        return patientId;
    }

    public void setPatientId(UUID patientId) {
        this.patientId = patientId;
    }

    public UUID getDeviceAssignmentId() {
        return deviceAssignmentId;
    }

    public void setDeviceAssignmentId(UUID deviceAssignmentId) {
        this.deviceAssignmentId = deviceAssignmentId;
    }
}
