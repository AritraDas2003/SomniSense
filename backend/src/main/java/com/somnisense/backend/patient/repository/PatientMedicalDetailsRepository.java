package com.somnisense.backend.patient.repository;

import com.somnisense.backend.patient.entity.PatientMedicalDetails;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PatientMedicalDetailsRepository extends JpaRepository<PatientMedicalDetails, UUID> {

    Optional<PatientMedicalDetails> findByPatientId(UUID patientId);
}
