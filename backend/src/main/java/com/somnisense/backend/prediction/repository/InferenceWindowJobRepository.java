package com.somnisense.backend.prediction.repository;

import com.somnisense.backend.prediction.entity.InferenceJobStatus;
import com.somnisense.backend.prediction.entity.InferenceWindowJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InferenceWindowJobRepository extends JpaRepository<InferenceWindowJob, UUID> {

    Optional<InferenceWindowJob> findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
            UUID sessionId, String modelVersion, Instant windowStart, Instant windowEnd);

    @Query("select max(j.windowEnd) from InferenceWindowJob j "
            + "where j.sessionId = :sessionId and j.modelVersion = :modelVersion")
    Optional<Instant> findMaxWindowEnd(@Param("sessionId") UUID sessionId,
                                       @Param("modelVersion") String modelVersion);

    List<InferenceWindowJob> findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAscWindowEndAsc(
            InferenceJobStatus status, Instant now, Pageable pageable);

    List<InferenceWindowJob> findBySessionIdAndStatus(UUID sessionId, InferenceJobStatus status);

    boolean existsBySessionIdAndStatus(UUID sessionId, InferenceJobStatus status);

    @Modifying
    @Query("update InferenceWindowJob j set j.status = com.somnisense.backend.prediction.entity.InferenceJobStatus.CANCELLED, "
            + "j.nextRetryAt = null, j.updatedAt = :now "
            + "where j.sessionId = :sessionId "
            + "and j.status = com.somnisense.backend.prediction.entity.InferenceJobStatus.RETRY_WAIT")
    int cancelOpenRetriesForSession(@Param("sessionId") UUID sessionId, @Param("now") Instant now);
}
