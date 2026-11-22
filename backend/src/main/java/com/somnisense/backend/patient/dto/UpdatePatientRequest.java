package com.somnisense.backend.patient.dto;

/**
 * Body of PUT /api/v1/patients/{id}: replaces fullName, alias, dateOfBirth and gender.
 * patientCode and sourceType can never be changed; status has its own Admin-only endpoint.
 * A "sourceType" field sent here is ignored.
 */
public class UpdatePatientRequest extends PatientProfileFields {
}
