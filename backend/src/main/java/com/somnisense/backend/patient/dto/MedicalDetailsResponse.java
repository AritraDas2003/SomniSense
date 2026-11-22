package com.somnisense.backend.patient.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.patient.entity.PatientMedicalDetails;

import java.time.Instant;
import java.util.UUID;

/** Medical block inside the patient detail response, with who last edited it and when. */
@JsonPropertyOrder({"medicalHistory", "sleepDisorder", "medications", "notes", "updatedBy", "updatedAt"})
public class MedicalDetailsResponse {

    private final String medicalHistory;
    private final String sleepDisorder;
    private final String medications;
    private final String notes;
    private final UUID updatedBy;
    private final Instant updatedAt;

    public MedicalDetailsResponse(PatientMedicalDetails m) {
        this.medicalHistory = m.getMedicalHistory();
        this.sleepDisorder = m.getSleepDisorder();
        this.medications = m.getMedications();
        this.notes = m.getNotes();
        this.updatedBy = m.getUpdatedBy();
        this.updatedAt = m.getUpdatedAt();
    }

    public String getMedicalHistory() {
        return medicalHistory;
    }

    public String getSleepDisorder() {
        return sleepDisorder;
    }

    public String getMedications() {
        return medications;
    }

    public String getNotes() {
        return notes;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
