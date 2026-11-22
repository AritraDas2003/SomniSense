package com.somnisense.backend.patient.dto;

import com.somnisense.backend.patient.entity.PatientStatus;
import jakarta.validation.constraints.NotNull;

/** Body of PATCH /api/v1/patients/{id}/status (ADMIN only): {"status":"INACTIVE"} */
public class ChangePatientStatusRequest {

    @NotNull
    private PatientStatus status;

    public PatientStatus getStatus() {
        return status;
    }

    public void setStatus(PatientStatus status) {
        this.status = status;
    }
}
