package com.somnisense.backend.prediction.repository;

import com.somnisense.backend.prediction.entity.Prediction;
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

/**
 * Prediction persistence and session-scoped queries using V5 indexes.
 */
public interface PredictionRepository extends JpaRepository<Prediction, UUID> {

    /**
     * One page of a session's predictions whose windowEnd falls in [from, to).
     * Ordering is supplied by the Pageable so V5 index (session_id, window_end DESC) is used.
     */
    @Query("select p from Prediction p where p.sessionId = :sessionId "
            + "and p.windowEnd >= :from and p.windowEnd < :to")
    Page<Prediction> findWindow(@Param("sessionId") UUID sessionId,
                                @Param("from") Instant from,
                                @Param("to") Instant to,
                                Pageable pageable);

    /** Deterministic newest prediction for the given session. */
    Optional<Prediction> findFirstBySessionIdOrderByWindowEndDescProcessedAtDescIdDesc(UUID sessionId);

    /** Looks up a prediction matching the V5 unique constraint. */
    Optional<Prediction> findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
            UUID sessionId, String modelVersion, Instant windowStart, Instant windowEnd);

    /** Returns the furthest evaluated windowEnd for this session and model version. */
    @Query("select max(p.windowEnd) from Prediction p "
            + "where p.sessionId = :sessionId and p.modelVersion = :modelVersion")
    Optional<Instant> findMaxWindowEnd(@Param("sessionId") UUID sessionId,
                                       @Param("modelVersion") String modelVersion);

    /**
     * Newest prediction per session for the configured model version in one PostgreSQL query.
     * Matches deterministic ordering: (window_end DESC, processed_at DESC, id DESC).
     */
    @Query(value = "select distinct on (session_id) * from predictions "
            + "where session_id in (:sessionIds) and model_version = :modelVersion "
            + "order by session_id, window_end desc, processed_at desc, id desc",
            nativeQuery = true)
    List<Prediction> findLatestPerSession(@Param("sessionIds") Collection<UUID> sessionIds,
                                          @Param("modelVersion") String modelVersion);
}
