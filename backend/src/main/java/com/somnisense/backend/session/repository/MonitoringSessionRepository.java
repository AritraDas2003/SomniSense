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

    List<MonitoringSession> findByStatusOrderByStartedAtAscIdAsc(SessionStatus status);

    List<MonitoringSession> findByStatusInOrderByStartedAtAscIdAsc(Collection<SessionStatus> statuses);

    /**
     * Eligible ACTIVE sessions plus ENDED sessions that still have at least one complete unevaluated
     * window (windowEnd <= endedAt) for the given model version.
     */
    @Query(value = "select * from monitoring_sessions s "
            + "where s.status = 'ACTIVE' "
            + "or (s.status = 'ENDED' and s.ended_at is not null "
            + "    and s.ended_at >= s.started_at + (cast(:windowDurationSeconds as bigint) * interval '1 second') "
            + "    and coalesce("
            + "          greatest("
            + "            (select max(p.window_end) from predictions p where p.session_id = s.id and p.model_version = :modelVersion), "
            + "            (select max(j.window_end) from inference_window_jobs j where j.session_id = s.id and j.model_version = :modelVersion)"
            + "          ) + (cast(:windowStrideSeconds as bigint) * interval '1 second'), "
            + "          s.started_at + (cast(:windowDurationSeconds as bigint) * interval '1 second')"
            + "        ) <= s.ended_at) "
            + "order by s.started_at asc, s.id asc",
            nativeQuery = true)
    List<MonitoringSession> findCandidateSessionsForScheduling(
            @Param("modelVersion") String modelVersion,
            @Param("windowDurationSeconds") long windowDurationSeconds,
            @Param("windowStrideSeconds") long windowStrideSeconds);

    /** Loads a session and locks its row (prevents end + cancel racing each other). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MonitoringSession s where s.id = :id")
    Optional<MonitoringSession> findByIdForUpdate(@Param("id") UUID id);
}
