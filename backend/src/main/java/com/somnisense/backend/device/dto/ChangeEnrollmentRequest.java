package com.somnisense.backend.device.dto;

import com.somnisense.backend.device.entity.EnrollmentStatus;
import jakarta.validation.constraints.NotNull;

/** Body of PATCH /api/v1/devices/{id}/enrollment: {"enrollmentStatus":"ENROLLED"} */
public class ChangeEnrollmentRequest {

    @NotNull
    private EnrollmentStatus enrollmentStatus;

    public EnrollmentStatus getEnrollmentStatus() {
        return enrollmentStatus;
    }

    public void setEnrollmentStatus(EnrollmentStatus enrollmentStatus) {
        this.enrollmentStatus = enrollmentStatus;
    }
}
