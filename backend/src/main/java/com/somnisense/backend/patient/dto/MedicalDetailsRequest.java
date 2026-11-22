package com.somnisense.backend.patient.dto;

import jakarta.validation.constraints.Size;

/**
 * Patient medical background (all optional free text). Used inside "create patient" and by
 * PUT /patients/{id}/medical-details, which replaces every field. Text columns are capped at 5000 characters.
 */
public class MedicalDetailsRequest {

    @Size(max = 5000)
    private String medicalHistory;

    @Size(max = 150)
    private String sleepDisorder;

    @Size(max = 5000)
    private String medications;

    @Size(max = 5000)
    private String notes;

    public MedicalDetailsRequest() {
    }

    public String getMedicalHistory() {
        return medicalHistory;
    }

    public void setMedicalHistory(String medicalHistory) {
        this.medicalHistory = medicalHistory;
    }

    public String getSleepDisorder() {
        return sleepDisorder;
    }

    public void setSleepDisorder(String sleepDisorder) {
        this.sleepDisorder = sleepDisorder;
    }

    public String getMedications() {
        return medications;
    }

    public void setMedications(String medications) {
        this.medications = medications;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
