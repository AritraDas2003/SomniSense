package com.somnisense.backend.prediction.service;

import com.somnisense.backend.prediction.dto.RecordPredictionCommand;
import com.somnisense.backend.prediction.dto.RecordPredictionResult;
import com.somnisense.backend.prediction.entity.InferenceJobStatus;
import com.somnisense.backend.prediction.entity.InferenceWindowJob;
import com.somnisense.backend.prediction.entity.PredictionStatus;
import com.somnisense.backend.prediction.repository.InferenceWindowJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Atomic persistence for scheduled inference window evaluations and durable retry state transitions.
 *
 * Retry policy (Stage 4 audit v2.3):
 * - 1 initial attempt + 3 retries = 4 maximum total attempts.
 * - Backoff schedule: +10s after attempt 1, +30s after attempt 2, +90s after attempt 3.
 * - Terminal ERROR rows are written to "predictions" ONLY on non-retryable errors or after attempt 4 is exhausted.
 */
@Service
public class InferenceJobService {

    public static final int MAX_ATTEMPTS = 4;
    private static final List<Long> BACKOFF_SECONDS = List.of(10L, 30L, 90L);

    private final InferenceWindowJobRepository jobRepository;
    private final PredictionService predictionService;

    public InferenceJobService(InferenceWindowJobRepository jobRepository, PredictionService predictionService) {
        this.jobRepository = jobRepository;
        this.predictionService = predictionService;
    }

    /**
     * Atomically persists a non-ERROR prediction (COMPLETED, NEEDS_REVIEW, or REJECTED)
     * and updates any existing retry job to SUCCEEDED.
     */
    @Transactional
    public RecordPredictionResult recordSuccess(RecordPredictionCommand command) {
        RecordPredictionResult result = predictionService.recordPrediction(command);
        Instant now = Instant.now();
        jobRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                command.getSessionId(), command.getModelVersion(), command.getWindowStart(), command.getWindowEnd()
        ).ifPresent(job -> {
            job.markSucceeded(job.getAttemptCount() + 1, now);
            jobRepository.save(job);
        });
        return result;
    }

    /**
     * Atomically records a retryable failure: schedules RETRY_WAIT up to MAX_ATTEMPTS,
     * or persists terminal PredictionStatus.ERROR + FAILED_EXHAUSTED on attempt 4.
     */
    @Transactional
    public InferenceWindowJob recordRetryableFailure(UUID sessionId, String modelVersion,
                                                     Instant windowStart, Instant windowEnd,
                                                     String errorMessage, int processingTimeMs) {
        Instant now = Instant.now();
        InferenceWindowJob job = jobRepository
                .findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(sessionId, modelVersion, windowStart, windowEnd)
                .orElseGet(() -> new InferenceWindowJob(
                        sessionId, modelVersion, windowStart, windowEnd,
                        InferenceJobStatus.RETRY_WAIT, 0, now, errorMessage, now));

        if (job.getStatus() == InferenceJobStatus.SUCCEEDED
                || job.getStatus() == InferenceJobStatus.FAILED_EXHAUSTED
                || job.getStatus() == InferenceJobStatus.CANCELLED) {
            return job;
        }

        int newAttempt = job.getAttemptCount() + 1;
        if (newAttempt < MAX_ATTEMPTS) {
            long delaySec = BACKOFF_SECONDS.get(Math.min(newAttempt - 1, BACKOFF_SECONDS.size() - 1));
            job.scheduleRetry(newAttempt, now.plusSeconds(delaySec), errorMessage, now);
            return jobRepository.save(job);
        }

        job.markExhausted(newAttempt, errorMessage, now);
        jobRepository.save(job);
        predictionService.recordPrediction(new RecordPredictionCommand(
                sessionId,
                modelVersion,
                windowStart,
                windowEnd,
                null,
                null,
                PredictionStatus.ERROR,
                null,
                now,
                processingTimeMs,
                errorMessage
        ));
        return job;
    }

    /**
     * Atomically records a non-retryable failure (e.g. 400/401/403/422) as terminal ERROR + FAILED_EXHAUSTED.
     */
    @Transactional
    public InferenceWindowJob recordNonRetryableFailure(UUID sessionId, String modelVersion,
                                                        Instant windowStart, Instant windowEnd,
                                                        String errorMessage, int processingTimeMs) {
        Instant now = Instant.now();
        InferenceWindowJob job = jobRepository
                .findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(sessionId, modelVersion, windowStart, windowEnd)
                .orElseGet(() -> new InferenceWindowJob(
                        sessionId, modelVersion, windowStart, windowEnd,
                        InferenceJobStatus.FAILED_EXHAUSTED, 0, null, errorMessage, now));

        int attempts = Math.max(1, job.getAttemptCount() + 1);
        job.markExhausted(attempts, errorMessage, now);
        jobRepository.save(job);

        predictionService.recordPrediction(new RecordPredictionCommand(
                sessionId,
                modelVersion,
                windowStart,
                windowEnd,
                null,
                null,
                PredictionStatus.ERROR,
                null,
                now,
                processingTimeMs,
                errorMessage
        ));
        return job;
    }

    @Transactional
    public int cancelOpenRetries(UUID sessionId) {
        return jobRepository.cancelOpenRetriesForSession(sessionId, Instant.now());
    }
}
