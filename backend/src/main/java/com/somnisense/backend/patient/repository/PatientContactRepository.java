package com.somnisense.backend.patient.repository;

import com.somnisense.backend.patient.entity.PatientContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PatientContactRepository extends JpaRepository<PatientContact, UUID> {

    Optional<PatientContact> findByPatientId(UUID patientId);
}
