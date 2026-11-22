package com.somnisense.backend.patient.dto;

import com.somnisense.backend.patient.entity.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * The editable profile fields shared by "create patient" and PUT /patients/{id}.
 * (Both request classes extend this, so the validation rules are written once.)
 *
 * dateOfBirth is a plain date "YYYY-MM-DD" (no time zone). It must be in the past; the service also
 * rejects dates before 1900-01-01.
 */
public class PatientProfileFields {

    @NotBlank
    @Size(max = 150)
    private String fullName;

    @Size(max = 100)
    private String alias;

    @Past
    private LocalDate dateOfBirth;

    private Gender gender;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }
}
