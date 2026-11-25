package com.somnisense.backend.reading.repository;

import com.somnisense.backend.reading.entity.SensorReading;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reading queries. All of them use the V4 index (session_id, measured_at). */
public interface SensorReadingRepository extends JpaRepository<SensorReading, UUID> {

    /** One page of a session's readings inside [from, to), oldest first (ORDER BY comes from the Pageable). */
    @Query("select r from SensorReading r where r.sessionId = :sessionId "
            + "and r.measuredAt >= :from and r.measuredAt < :to")
    Page<SensorReading> findWindow(@Param("sessionId") UUID sessionId, @Param("from") Instant from,
                                   @Param("to") Instant to, Pageable pageable);

    /** Deterministic causal context readings in [from, to) ordered by (measuredAt, receivedAt, id). */
    @Query("select r from SensorReading r where r.sessionId = :sessionId "
            + "and r.measuredAt >= :from and r.measuredAt < :to "
            + "order by r.measuredAt asc, r.receivedAt asc, r.id asc")
    List<SensorReading> findWindowOrdered(@Param("sessionId") UUID sessionId,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to);

    /** The most recent reading of a session, whatever its values (may contain nulls = dropout). */
    Optional<SensorReading> findFirstBySessionIdOrderByMeasuredAtDesc(UUID sessionId);

    /** The most recent reading that actually has an SpO2 value. */
    Optional<SensorReading> findFirstBySessionIdAndSpo2IsNotNullOrderByMeasuredAtDesc(UUID sessionId);

    /** The most recent reading that actually has a heart-rate value. */
    Optional<SensorReading> findFirstBySessionIdAndHeartRateIsNotNullOrderByMeasuredAtDesc(UUID sessionId);

    /**
     * The latest reading of EACH given session in one query (dashboard list).
     * PostgreSQL "DISTINCT ON (session_id)" keeps the first row per session after ordering by time descending.
     */
    @Query(value = "select distinct on (session_id) * from sensor_readings "
            + "where session_id in (:sessionIds) order by session_id, measured_at desc", nativeQuery = true)
    List<SensorReading> findLatestPerSession(@Param("sessionIds") Collection<UUID> sessionIds);
}
