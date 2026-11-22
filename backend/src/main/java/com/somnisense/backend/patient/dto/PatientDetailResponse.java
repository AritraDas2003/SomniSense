package com.somnisense.backend.patient.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.assignment.dto.CurrentDeviceInfo;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.reading.dto.LatestReadingInfo;
import com.somnisense.backend.session.dto.ActiveSessionInfo;
import com.somnisense.backend.patient.entity.PatientContact;
import com.somnisense.backend.patient.entity.PatientMedicalDetails;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Full patient profile (GET /patients/{id}, create, update responses).
 * contact / medicalDetails are null when they have never been entered.
 */
@JsonPropertyOrder({"id", "patientCode", "fullName", "alias", "dateOfBirth", "gender", "sourceType", "status",
        "currentDevice", "activeSession", "latestReading", "createdBy", "createdAt", "updatedAt", "contact", "medicalDetails"})
public class PatientDetailResponse {

    private final UUID id;
    private final String patientCode;
    private final String fullName;
    private final String alias;
    private final LocalDate dateOfBirth;
    private final String gender;
    private final String sourceType;
    private final String status;
    private final UUID createdBy;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final ContactResponse contact;
    private final MedicalDetailsResponse medicalDetails;
    private final CurrentDeviceInfo currentDevice;
    private final ActiveSessionInfo activeSession;
    private final LatestReadingInfo latestReading;

    public PatientDetailResponse(Patient p, PatientContact contact, PatientMedicalDetails medical,
                                 CurrentDeviceInfo currentDevice, ActiveSessionInfo activeSession,
                                 LatestReadingInfo latestReading) {
        this.currentDevice = currentDevice;
        this.activeSession = activeSession;
        this.latestReading = latestReading;
        this.id = p.getId();
        this.patientCode = p.getPatientCode();
        this.fullName = p.getFullName();
        this.alias = p.getAlias();
        this.dateOfBirth = p.getDateOfBirth();
        this.gender = p.getGender() == null ? null : p.getGender().name();
        this.sourceType = p.getSourceType().name();
        this.status = p.getStatus().name();
        this.createdBy = p.getCreatedBy();
        this.createdAt = p.getCreatedAt();
        this.updatedAt = p.getUpdatedAt();
        this.contact = contact == null ? null : new ContactResponse(contact);
        this.medicalDetails = medical == null ? null : new MedicalDetailsResponse(medical);
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

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public String getGender() {
        return gender;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public ContactResponse getContact() {
        return contact;
    }

    public MedicalDetailsResponse getMedicalDetails() {
        return medicalDetails;
    }

    public CurrentDeviceInfo getCurrentDevice() {
        return currentDevice;
    }

    public ActiveSessionInfo getActiveSession() {
        return activeSession;
    }

    public LatestReadingInfo getLatestReading() {
        return latestReading;
    }
}
