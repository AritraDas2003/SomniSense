package com.somnisense.backend.assignment.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of POST /api/v1/device-assignments:
 * {"deviceId":"...", "patientId":"...", "replaceExisting": false}
 *
 * replaceExisting (default false): if the device or the patient already has an active assignment,
 * false -> 409 so the UI can ask the user to confirm (UI plan C37);
 * true  -> the old assignment(s) are released and the new one created, all in one transaction.
 */
public class AssignDeviceRequest {

    @NotNull
    private UUID deviceId;

    @NotNull
    private UUID patientId;

    private boolean replaceExisting;

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public void setPatientId(UUID patientId) {
        this.patientId = patientId;
    }

    public boolean isReplaceExisting() {
        return replaceExisting;
    }

    public void setReplaceExisting(boolean replaceExisting) {
        this.replaceExisting = replaceExisting;
    }
}
