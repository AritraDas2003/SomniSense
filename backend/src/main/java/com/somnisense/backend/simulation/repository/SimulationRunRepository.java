package com.somnisense.backend.simulation.repository;

import com.somnisense.backend.simulation.entity.SimulationRun;
import com.somnisense.backend.simulation.entity.SimulationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SimulationRunRepository extends JpaRepository<SimulationRun, UUID> {

    Optional<SimulationRun> findBySessionId(UUID sessionId);

    List<SimulationRun> findBySessionIdIn(Collection<UUID> sessionIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SimulationRun r where r.sessionId = :sessionId")
    Optional<SimulationRun> findBySessionIdForUpdate(@Param("sessionId") UUID sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SimulationRun r where r.id = :id")
    Optional<SimulationRun> findByIdForUpdate(@Param("id") UUID id);

    List<SimulationRun> findByStatusAndLeaseExpiresAtBefore(SimulationStatus status, Instant cutoff);

    @Modifying
    @Query("update SimulationRun r "
            + "set r.status = com.somnisense.backend.simulation.entity.SimulationStatus.PAUSED, "
            + "r.leaseExpiresAt = null, r.workerInstanceId = null, "
            + "r.updatedAt = case when :now < r.startedAt then r.startedAt else :now end "
            + "where r.status = com.somnisense.backend.simulation.entity.SimulationStatus.RUNNING")
    int pauseAllRunningOnStartup(@Param("now") Instant now);
}
