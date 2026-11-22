package com.somnisense.backend.patient.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Patient contact details (all optional). Used inside "create patient" and by PUT /patients/{id}/contact.
 * PUT replaces every field: a field left out or blank is cleared.
 * Lengths match the approved columns; address_line is "text" in SQL, capped here at 500 to stop abuse.
 */
public class ContactRequest {

    /** Digits, spaces and + ( ) - . only. */
    public static final String PHONE_REGEX = "^[0-9+()\\-.\\s]{3,25}$";

    @Size(max = 25)
    @Pattern(regexp = PHONE_REGEX, message = "must contain only digits, spaces and + ( ) - .")
    private String phone;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 500)
    private String addressLine;

    @Size(max = 100)
    private String city;

    @Size(max = 100)
    private String state;

    @Size(max = 15)
    private String postalCode;

    @Size(max = 150)
    private String emergencyContactName;

    @Size(max = 25)
    @Pattern(regexp = PHONE_REGEX, message = "must contain only digits, spaces and + ( ) - .")
    private String emergencyContactPhone;

    @Size(max = 50)
    private String emergencyContactRelationship;

    public ContactRequest() {
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public void setAddressLine(String addressLine) {
        this.addressLine = addressLine;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getEmergencyContactName() {
        return emergencyContactName;
    }

    public void setEmergencyContactName(String emergencyContactName) {
        this.emergencyContactName = emergencyContactName;
    }

    public String getEmergencyContactPhone() {
        return emergencyContactPhone;
    }

    public void setEmergencyContactPhone(String emergencyContactPhone) {
        this.emergencyContactPhone = emergencyContactPhone;
    }

    public String getEmergencyContactRelationship() {
        return emergencyContactRelationship;
    }

    public void setEmergencyContactRelationship(String emergencyContactRelationship) {
        this.emergencyContactRelationship = emergencyContactRelationship;
    }
}
