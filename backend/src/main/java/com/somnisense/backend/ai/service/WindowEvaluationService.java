package com.somnisense.backend.ai.service;

import com.somnisense.backend.ai.client.AiSidecarClient;
import com.somnisense.backend.ai.client.AiSidecarException;
import com.somnisense.backend.ai.config.AiProperties;
import com.somnisense.backend.ai.config.AiSchedulerProperties;
import com.somnisense.backend.ai.dto.AiEvaluateWindowRequest;
import com.somnisense.backend.ai.dto.AiEvaluateWindowResponse;
import com.somnisense.backend.ai.dto.AiReadingSample;
import com.somnisense.backend.common.exception.ConflictException;
import com.somnisense.backend.common.exception.InvalidRequestException;
import com.somnisense.backend.common.exception.ResourceNotFoundException;
import com.somnisense.backend.prediction.dto.RecordPredictionCommand;
import com.somnisense.backend.prediction.dto.RecordPredictionResult;
import com.somnisense.backend.prediction.entity.PredictionStatus;
import com.somnisense.backend.prediction.service.PredictionService;
import com.somnisense.backend.reading.entity.SensorReading;
import com.somnisense.backend.reading.repository.SensorReadingRepository;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates single-window Model C evaluation via the internal FastAPI sidecar.
 *
 * Preserves Stage 3 evaluateAndPersist(...) while exposing evaluateOnce(...) for the Stage 4
 * scheduler so retryable AI failures enter durable RETRY_WAIT instead of premature ERROR persistence.
 */
@Service
public class WindowEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(WindowEvaluationService.class);
    private static final Duration DEFAULT_WINDOW_DURATION = Duration.ofSeconds(60);
    private static final Duration DEFAULT_CAUSAL_CONTEXT_DURATION = Duration.ofSeconds(360);

    private final MonitoringSessionRepository sessionRepository;
    private final SensorReadingRepository readingRepository;
    private final AiSidecarClient aiSidecarClient;
    private final PredictionService predictionService;
    private final AiProperties aiProperties;
    private final Duration windowDuration;
    private final Duration causalContextDuration;

    public WindowEvaluationService(MonitoringSessionRepository sessionRepository,
                                   SensorReadingRepository readingRepository,
                                   AiSidecarClient aiSidecarClient,
                                   PredictionService predictionService,
                                   AiProperties aiProperties) {
        this(sessionRepository, readingRepository, aiSidecarClient, predictionService, aiProperties,
                DEFAULT_WINDOW_DURATION, DEFAULT_CAUSAL_CONTEXT_DURATION);
    }

    @Autowired
    public WindowEvaluationService(MonitoringSessionRepository sessionRepository,
                                   SensorReadingRepository readingRepository,
                                   AiSidecarClient aiSidecarClient,
                                   PredictionService predictionService,
                                   AiProperties aiProperties,
                                   AiSchedulerProperties schedulerProperties) {
        this(sessionRepository, readingRepository, aiSidecarClient, predictionService, aiProperties,
                schedulerProperties.getWindowDuration(), schedulerProperties.getCausalContextDuration());
    }

    private WindowEvaluationService(MonitoringSessionRepository sessionRepository,
                                    SensorReadingRepository readingRepository,
                                    AiSidecarClient aiSidecarClient,
                                    PredictionService predictionService,
                                    AiProperties aiProperties,
                                    Duration windowDuration,
                                    Duration causalContextDuration) {
        this.sessionRepository = sessionRepository;
        this.readingRepository = readingRepository;
        this.aiSidecarClient = aiSidecarClient;
        this.predictionService = predictionService;
        this.aiProperties = aiProperties;
        this.windowDuration = windowDuration;
        this.causalContextDuration = causalContextDuration;
    }

    /** Outcome of one sidecar evaluation attempt before persistence is committed. */
    public record SidecarCallOutcome(
            boolean succeeded,
            boolean retryable,
            RecordPredictionCommand predictionCommand,
            String modelVersion,
            String errorMessage,
            int processingTimeMs
    ) {
        public static SidecarCallOutcome success(RecordPredictionCommand command, int processingTimeMs) {
            return new SidecarCallOutcome(true, false, command, command.getModelVersion(), null, processingTimeMs);
        }

        public static SidecarCallOutcome failure(boolean retryable, String modelVersion,
                                                 String errorMessage, int processingTimeMs) {
            return new SidecarCallOutcome(false, retryable, null, modelVersion, errorMessage, processingTimeMs);
        }
    }

    /**
     * Stage 3 direct evaluation method: evaluates once and immediately persists the prediction or ERROR.
     */
    public RecordPredictionResult evaluateAndPersist(UUID sessionId, Instant windowStart, Instant windowEnd) {
        AiEvaluateWindowRequest request = prepareRequest(sessionId, windowStart, windowEnd);
        long startedNanos = System.nanoTime();
        try {
            AiEvaluateWindowResponse aiResponse = aiSidecarClient.evaluateWindow(request);
            return predictionService.recordPrediction(toCommand(sessionId, windowStart, windowEnd,
                    aiResponse, startedNanos, Instant.now()));
        } catch (AiSidecarException ex) {
            int elapsedMs = elapsedMillis(startedNanos);
            log.warn("AI sidecar evaluation failed for session {} window [{}, {}): {}",
                    sessionId, windowStart, windowEnd, ex.getMessage());
            return predictionService.recordPrediction(new RecordPredictionCommand(
                    sessionId,
                    aiProperties.getModelVersion(),
                    windowStart,
                    windowEnd,
                    null,
                    null,
                    PredictionStatus.ERROR,
                    null,
                    Instant.now(),
                    elapsedMs,
                    ex.getMessage()
            ));
        }
    }

    /**
     * Stage 4 scheduler-facing path: prepares ordered 360-second causal context in short repository
     * reads (closing any DB connection prior to HTTP), invokes the AI sidecar outside transactions,
     * and returns a retry-classified outcome without persisting prematurely.
     */
    public SidecarCallOutcome evaluateOnce(UUID sessionId, Instant windowStart, Instant windowEnd, Instant now) {
        Instant processedAt = now != null ? now : Instant.now();
        AiEvaluateWindowRequest request = prepareRequest(sessionId, windowStart, windowEnd);

        long startedNanos = System.nanoTime();
        try {
            AiEvaluateWindowResponse aiResponse = aiSidecarClient.evaluateWindow(request);
            RecordPredictionCommand command = toCommand(sessionId, windowStart, windowEnd,
                    aiResponse, startedNanos, processedAt);
            return SidecarCallOutcome.success(command, command.getProcessingTimeMs() != null
                    ? command.getProcessingTimeMs()
                    : elapsedMillis(startedNanos));
        } catch (AiSidecarException ex) {
            int elapsedMs = elapsedMillis(startedNanos);
            log.warn("Scheduled AI sidecar call failed (retryable={}, status={}) for session {} window [{}, {}): {}",
                    ex.isRetryable(), ex.getHttpStatus(), sessionId, windowStart, windowEnd, ex.getMessage());
            return SidecarCallOutcome.failure(
                    ex.isRetryable(),
                    aiProperties.getModelVersion(),
                    ex.getMessage(),
                    elapsedMs
            );
        }
    }

    private AiEvaluateWindowRequest prepareRequest(UUID sessionId, Instant windowStart, Instant windowEnd) {
        if (sessionId == null || windowStart == null || windowEnd == null) {
            throw new InvalidRequestException("sessionId, windowStart, and windowEnd are required");
        }
        if (!Duration.between(windowStart, windowEnd).equals(windowDuration)) {
            throw new InvalidRequestException("Evaluation window duration must be exactly "
                    + windowDuration.getSeconds() + " seconds");
        }

        MonitoringSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));
        if (session.getStatus() == SessionStatus.CANCELLED) {
            throw new ConflictException("Cannot evaluate windows for a CANCELLED monitoring session");
        }

        Instant contextStart = windowEnd.minus(causalContextDuration);
        List<SensorReading> orderedReadings = readingRepository.findWindowOrdered(sessionId, contextStart, windowEnd);
        List<AiReadingSample> samples = orderedReadings.stream().map(AiReadingSample::from).toList();

        return new AiEvaluateWindowRequest(
                sessionId,
                windowStart,
                windowEnd,
                contextStart,
                samples
        );
    }

    private RecordPredictionCommand toCommand(UUID sessionId, Instant windowStart, Instant windowEnd,
                                              AiEvaluateWindowResponse aiResponse, long startedNanos,
                                              Instant processedAt) {
        int fallbackMs = elapsedMillis(startedNanos);
        int processingMs = aiResponse.processingTimeMs() != null ? aiResponse.processingTimeMs() : fallbackMs;
        String modelVersion = aiResponse.modelVersion() != null && !aiResponse.modelVersion().isBlank()
                ? aiResponse.modelVersion()
                : aiProperties.getModelVersion();

        return new RecordPredictionCommand(
                sessionId,
                modelVersion,
                windowStart,
                windowEnd,
                aiResponse.predictedClass(),
                aiResponse.confidenceScore(),
                aiResponse.predictionStatus(),
                aiResponse.inputQualityStatus(),
                processedAt,
                processingMs,
                aiResponse.errorMessage()
        );
    }

    private static int elapsedMillis(long startedNanos) {
        return (int) Math.max(0L, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }
}
