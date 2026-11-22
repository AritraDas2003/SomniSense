package com.somnisense.backend.patient.entity;

import com.somnisense.backend.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.util.Objects;
import java.util.UUID;

import static com.somnisense.backend.common.util.Strings.clean;

/**
 * Optional contact details of a patient (approved "patient_contacts" table; at most one row per patient
 * because patient_id is UNIQUE). Every field is optional. Sensitive: never returned in patient LISTS.
 */
@Entity
@Table(name = "patient_contacts")
@DynamicUpdate
public class PatientContact extends AssignedIdEntity {

    @Column(name = "patient_id", nullable = false, unique = true, updatable = false)
    private UUID patientId;

    @Column(name = "phone", length = 25)
    private String phone;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "address_line")   // SQL type "text": no length limit in the database
    private String addressLine;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "postal_code", length = 15)
    private String postalCode;

    @Column(name = "emergency_contact_name", length = 150)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 25)
    private String emergencyContactPhone;

    @Column(name = "emergency_contact_relationship", length = 50)
    private String emergencyContactRelationship;

    protected PatientContact() {
    }

    public PatientContact(UUID patientId) {
        super(UUID.randomUUID());
        this.patientId = Objects.requireNonNull(patientId, "patientId");
    }

    /** Replaces all contact fields (blank text becomes null). */
    public void update(String phone, String email, String addressLine, String city, String state, String postalCode,
                       String emergencyContactName, String emergencyContactPhone,
                       String emergencyContactRelationship) {
        this.phone = clean(phone);
        this.email = clean(email) == null ? null : clean(email).toLowerCase(java.util.Locale.ROOT);
        this.addressLine = clean(addressLine);
        this.city = clean(city);
        this.state = clean(state);
        this.postalCode = clean(postalCode);
        this.emergencyContactName = clean(emergencyContactName);
        this.emergencyContactPhone = clean(emergencyContactPhone);
        this.emergencyContactRelationship = clean(emergencyContactRelationship);
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getEmergencyContactName() {
        return emergencyContactName;
    }

    public String getEmergencyContactPhone() {
        return emergencyContactPhone;
    }

    public String getEmergencyContactRelationship() {
        return emergencyContactRelationship;
    }
}
