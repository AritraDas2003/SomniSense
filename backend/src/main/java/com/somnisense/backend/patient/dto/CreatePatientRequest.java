package com.somnisense.backend.patient.dto;

import com.somnisense.backend.common.domain.SourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/v1/patients.
 * <pre>
 * {
 *   "fullName": "Synthetic Patient 01", "alias": "SP-01", "dateOfBirth": "1980-05-17", "gender": "FEMALE",
 *   "sourceType": "SIMULATED",
 *   "contact": { ... optional ... },
 *   "medicalDetails": { ... optional ... }
 * }
 * </pre>
 * There is no patientCode field: the backend generates it. @Valid on the nested objects makes their rules apply too.
 */
public class CreatePatientRequest extends PatientProfileFields {

    @NotNull
    private SourceType sourceType;

    @Valid
    private ContactRequest contact;

    @Valid
    private MedicalDetailsRequest medicalDetails;

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public ContactRequest getContact() {
        return contact;
    }

    public void setContact(ContactRequest contact) {
        this.contact = contact;
    }

    public MedicalDetailsRequest getMedicalDetails() {
        return medicalDetails;
    }

    public void setMedicalDetails(MedicalDetailsRequest medicalDetails) {
        this.medicalDetails = medicalDetails;
    }
}
