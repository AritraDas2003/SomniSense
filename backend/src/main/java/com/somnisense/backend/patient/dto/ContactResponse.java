package com.somnisense.backend.patient.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.patient.entity.PatientContact;

/** Contact block inside the patient detail response. */
@JsonPropertyOrder({"phone", "email", "addressLine", "city", "state", "postalCode",
        "emergencyContactName", "emergencyContactPhone", "emergencyContactRelationship"})
public class ContactResponse {

    private final String phone;
    private final String email;
    private final String addressLine;
    private final String city;
    private final String state;
    private final String postalCode;
    private final String emergencyContactName;
    private final String emergencyContactPhone;
    private final String emergencyContactRelationship;

    public ContactResponse(PatientContact c) {
        this.phone = c.getPhone();
        this.email = c.getEmail();
        this.addressLine = c.getAddressLine();
        this.city = c.getCity();
        this.state = c.getState();
        this.postalCode = c.getPostalCode();
        this.emergencyContactName = c.getEmergencyContactName();
        this.emergencyContactPhone = c.getEmergencyContactPhone();
        this.emergencyContactRelationship = c.getEmergencyContactRelationship();
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
