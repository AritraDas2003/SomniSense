package com.somnisense.backend.session.repository;

import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.entity.SessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonitoringSessionRepository extends JpaRepository<MonitoringSession, UUID>,
        JpaSpecificationExecutor<MonitoringSession> {

    boolean existsBySessionCode(String sessionCode);

    Optional<MonitoringSession> findByPatientIdAndStatus(UUID patientId, SessionStatus status);

    boolean existsByDeviceAssignmentIdAndStatus(UUID deviceAssignmentId, SessionStatus status);

    /** Active sessions for a page of patients (dashboard) in one query. */
    List<MonitoringSession> findByPatientIdInAndStatus(Collection<UUID> patientIds, SessionStatus status);

    /** Loads a session and locks its row (prevents end + cancel racing each other). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MonitoringSession s where s.id = :id")
    Optional<MonitoringSession> findByIdForUpdate(@Param("id") UUID id);
}
