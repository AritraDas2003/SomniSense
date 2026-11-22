package com.somnisense.backend.patient.entity;

import com.somnisense.backend.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static com.somnisense.backend.common.util.Strings.clean;

/**
 * Optional medical background of a patient (approved "patient_medical_details" table; at most one row per patient).
 * Free text entered by staff - the system never interprets it clinically.
 * SENSITIVE: never returned in lists, never written to logs or audit details.
 */
@Entity
@Table(name = "patient_medical_details")
@DynamicUpdate
public class PatientMedicalDetails extends AssignedIdEntity {

    @Column(name = "patient_id", nullable = false, unique = true, updatable = false)
    private UUID patientId;

    @Column(name = "medical_history")
    private String medicalHistory;

    @Column(name = "sleep_disorder", length = 150)
    private String sleepDisorder;

    @Column(name = "medications")
    private String medications;

    @Column(name = "notes")
    private String notes;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PatientMedicalDetails() {
    }

    public PatientMedicalDetails(UUID patientId) {
        super(UUID.randomUUID());
        this.patientId = Objects.requireNonNull(patientId, "patientId");
        this.updatedAt = Instant.now();
    }

    /** Replaces all fields and records who changed them and when. */
    public void update(String medicalHistory, String sleepDisorder, String medications, String notes, UUID updatedBy) {
        this.medicalHistory = clean(medicalHistory);
        this.sleepDisorder = clean(sleepDisorder);
        this.medications = clean(medications);
        this.notes = clean(notes);
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public UUID getPatientId() {
        return patientId;
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
