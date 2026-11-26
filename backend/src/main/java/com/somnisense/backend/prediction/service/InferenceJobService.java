package com.somnisense.backend.prediction.service;

import com.somnisense.backend.ai.config.AiSchedulerProperties;
import com.somnisense.backend.prediction.dto.RecordPredictionCommand;
import com.somnisense.backend.prediction.dto.RecordPredictionResult;
import com.somnisense.backend.prediction.entity.InferenceJobStatus;
import com.somnisense.backend.prediction.entity.InferenceWindowJob;
import com.somnisense.backend.prediction.entity.PredictionStatus;
import com.somnisense.backend.prediction.repository.InferenceWindowJobRepository;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Atomic persistence for scheduled inference window evaluations and durable retry state transitions.
 *
 * Defaults per Stage 4 audit v2.3 (configurable via AiSchedulerProperties):
 * - 1 initial attempt + 3 retries = 4 maximum total attempts.
 * - Backoff schedule: +10s after attempt 1, +30s after attempt 2, +90s after attempt 3.
 * - Terminal ERROR rows are written to "predictions" ONLY on non-retryable errors or after exhaustion.
 */
@Service
public class InferenceJobService {

    public static final int MAX_ATTEMPTS = 4;
    private static final List<Long> DEFAULT_BACKOFF_SECONDS = List.of(10L, 30L, 90L);

    private final InferenceWindowJobRepository jobRepository;
    private final PredictionService predictionService;
    private final MonitoringSessionRepository sessionRepository;
    private final TransactionTemplate transactionTemplate;
    private final int maxAttempts;
    private final List<Long> backoffSeconds;

    @Autowired
    public InferenceJobService(InferenceWindowJobRepository jobRepository,
                               PredictionService predictionService,
                               MonitoringSessionRepository sessionRepository,
                               AiSchedulerProperties schedulerProperties,
                               PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.predictionService = predictionService;
        this.sessionRepository = sessionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxAttempts = schedulerProperties != null ? schedulerProperties.getMaxRetryAttempts() : MAX_ATTEMPTS;
        this.backoffSeconds = schedulerProperties != null && !schedulerProperties.getRetryBackoffSeconds().isEmpty()
                ? schedulerProperties.getRetryBackoffSeconds()
                : DEFAULT_BACKOFF_SECONDS;
    }

    /**
     * Atomically persists a non-ERROR prediction (COMPLETED, NEEDS_REVIEW, or REJECTED)
     * and updates any existing retry job to SUCCEEDED.
     */
    public RecordPredictionResult recordSuccess(RecordPredictionCommand command) {
        return recordSuccess(command, Instant.now());
    }

    public RecordPredictionResult recordSuccess(RecordPredictionCommand command, Instant now) {
        return transactionTemplate.execute(tx -> {
            Instant effectiveNow = now != null ? now : Instant.now();
            Optional<MonitoringSession> session = sessionRepository.findById(command.getSessionId());
            if (session.isPresent() && session.get().getStatus() == SessionStatus.CANCELLED) {
                jobRepository.cancelOpenRetriesForSession(command.getSessionId(), effectiveNow);
                return null;
            }
            RecordPredictionResult result = predictionService.recordPrediction(command);
            jobRepository.findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(
                    command.getSessionId(), command.getModelVersion(), command.getWindowStart(), command.getWindowEnd()
            ).ifPresent(job -> {
                if (job.getStatus() == InferenceJobStatus.RETRY_WAIT) {
                    job.markSucceeded(job.getAttemptCount() + 1, clampAfter(effectiveNow, job.getCreatedAt()));
                    jobRepository.save(job);
                }
            });
            return result;
        });
    }

    public InferenceWindowJob recordRetryableFailure(UUID sessionId, String modelVersion,
                                                     Instant windowStart, Instant windowEnd,
                                                     String errorMessage, int processingTimeMs) {
        return recordRetryableFailure(sessionId, modelVersion, windowStart, windowEnd,
                errorMessage, processingTimeMs, Instant.now());
    }

    /**
     * Atomically records a retryable failure: schedules RETRY_WAIT up to maxAttempts,
     * or persists terminal PredictionStatus.ERROR + FAILED_EXHAUSTED when exhausted.
     */
    public InferenceWindowJob recordRetryableFailure(UUID sessionId, String modelVersion,
                                                     Instant windowStart, Instant windowEnd,
                                                     String errorMessage, int processingTimeMs, Instant now) {
        return transactionTemplate.execute(tx -> {
            Instant effectiveNow = now != null ? now : Instant.now();
            Optional<MonitoringSession> session = sessionRepository.findById(sessionId);
            if (session.isPresent() && session.get().getStatus() == SessionStatus.CANCELLED) {
                jobRepository.cancelOpenRetriesForSession(sessionId, effectiveNow);
                return null;
            }

            Optional<InferenceWindowJob> existingOpt = jobRepository
                    .findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(sessionId, modelVersion, windowStart, windowEnd);
            if (existingOpt.isPresent()) {
                InferenceWindowJob existing = existingOpt.get();
                if (existing.getStatus() == InferenceJobStatus.SUCCEEDED
                        || existing.getStatus() == InferenceJobStatus.FAILED_EXHAUSTED
                        || existing.getStatus() == InferenceJobStatus.CANCELLED) {
                    return existing;
                }
            }

            InferenceWindowJob job = existingOpt.orElseGet(() -> new InferenceWindowJob(
                    sessionId, modelVersion, windowStart, windowEnd,
                    InferenceJobStatus.RETRY_WAIT, 0, effectiveNow, errorMessage, effectiveNow));

            Instant safeNow = clampAfter(effectiveNow, job.getCreatedAt());
            int newAttempt = existingOpt.isPresent() ? job.getAttemptCount() + 1 : 1;
            if (newAttempt < maxAttempts) {
                long delaySec = backoffSeconds.get(Math.min(newAttempt - 1, backoffSeconds.size() - 1));
                job.scheduleRetry(newAttempt, safeNow.plusSeconds(delaySec), errorMessage, safeNow);
                return jobRepository.save(job);
            }

            job.markExhausted(newAttempt, errorMessage, safeNow);
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
                    safeNow,
                    processingTimeMs,
                    errorMessage
            ));
            return job;
        });
    }

    public InferenceWindowJob recordNonRetryableFailure(UUID sessionId, String modelVersion,
                                                        Instant windowStart, Instant windowEnd,
                                                        String errorMessage, int processingTimeMs) {
        return recordNonRetryableFailure(sessionId, modelVersion, windowStart, windowEnd,
                errorMessage, processingTimeMs, Instant.now());
    }

    /**
     * Atomically records a non-retryable failure (e.g. 400/401/403/422) as terminal ERROR + FAILED_EXHAUSTED.
     */
    public InferenceWindowJob recordNonRetryableFailure(UUID sessionId, String modelVersion,
                                                        Instant windowStart, Instant windowEnd,
                                                        String errorMessage, int processingTimeMs, Instant now) {
        return transactionTemplate.execute(tx -> {
            Instant effectiveNow = now != null ? now : Instant.now();
            Optional<MonitoringSession> session = sessionRepository.findById(sessionId);
            if (session.isPresent() && session.get().getStatus() == SessionStatus.CANCELLED) {
                jobRepository.cancelOpenRetriesForSession(sessionId, effectiveNow);
                return null;
            }

            Optional<InferenceWindowJob> existingOpt = jobRepository
                    .findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(sessionId, modelVersion, windowStart, windowEnd);
            if (existingOpt.isPresent()) {
                InferenceWindowJob existing = existingOpt.get();
                if (existing.getStatus() == InferenceJobStatus.SUCCEEDED
                        || existing.getStatus() == InferenceJobStatus.FAILED_EXHAUSTED
                        || existing.getStatus() == InferenceJobStatus.CANCELLED) {
                    return existing;
                }
            }

            InferenceWindowJob job = existingOpt.orElseGet(() -> new InferenceWindowJob(
                    sessionId, modelVersion, windowStart, windowEnd,
                    InferenceJobStatus.RETRY_WAIT, 0, null, errorMessage, effectiveNow));

            Instant safeNow = clampAfter(effectiveNow, job.getCreatedAt());
            int attempts = existingOpt.isPresent() ? job.getAttemptCount() + 1 : 1;
            job.markExhausted(attempts, errorMessage, safeNow);
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
                    safeNow,
                    processingTimeMs,
                    errorMessage
            ));
            return job;
        });
    }

    public int cancelOpenRetries(UUID sessionId) {
        return cancelOpenRetries(sessionId, Instant.now());
    }

    public int cancelOpenRetries(UUID sessionId, Instant now) {
        Integer count = transactionTemplate.execute(tx ->
                jobRepository.cancelOpenRetriesForSession(sessionId, now != null ? now : Instant.now()));
        return count != null ? count : 0;
    }

    private static Instant clampAfter(Instant candidate, Instant floor) {
        return candidate.isBefore(floor) ? floor : candidate;
    }
}
