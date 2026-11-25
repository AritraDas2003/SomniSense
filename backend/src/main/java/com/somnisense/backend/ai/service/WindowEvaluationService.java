package com.somnisense.backend.ai.service;

import com.somnisense.backend.ai.client.AiSidecarClient;
import com.somnisense.backend.ai.client.AiSidecarException;
import com.somnisense.backend.ai.config.AiProperties;
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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates single-window Model C evaluation via the internal FastAPI sidecar
 * and persists the resulting prediction (or ERROR outcome) through PredictionService.
 */
@Service
public class WindowEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(WindowEvaluationService.class);
    private static final Duration WINDOW_DURATION = Duration.ofSeconds(60);
    private static final Duration CAUSAL_CONTEXT_DURATION = Duration.ofSeconds(360);

    private final MonitoringSessionRepository sessionRepository;
    private final SensorReadingRepository readingRepository;
    private final AiSidecarClient aiSidecarClient;
    private final PredictionService predictionService;
    private final AiProperties aiProperties;

    public WindowEvaluationService(MonitoringSessionRepository sessionRepository,
                                   SensorReadingRepository readingRepository,
                                   AiSidecarClient aiSidecarClient,
                                   PredictionService predictionService,
                                   AiProperties aiProperties) {
        this.sessionRepository = sessionRepository;
        this.readingRepository = readingRepository;
        this.aiSidecarClient = aiSidecarClient;
        this.predictionService = predictionService;
        this.aiProperties = aiProperties;
    }

    /**
     * Evaluates a 60-second window [windowStart, windowEnd) using 360 seconds of causal context
     * [windowEnd - 360s, windowEnd) and persists the outcome idempotently.
     */
    public RecordPredictionResult evaluateAndPersist(UUID sessionId, Instant windowStart, Instant windowEnd) {
        if (sessionId == null || windowStart == null || windowEnd == null) {
            throw new InvalidRequestException("sessionId, windowStart, and windowEnd are required");
        }
        if (!Duration.between(windowStart, windowEnd).equals(WINDOW_DURATION)) {
            throw new InvalidRequestException("Evaluation window duration must be exactly 60 seconds");
        }

        MonitoringSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));
        if (session.getStatus() == SessionStatus.CANCELLED) {
            throw new ConflictException("Cannot evaluate windows for a CANCELLED monitoring session");
        }

        Instant contextStart = windowEnd.minus(CAUSAL_CONTEXT_DURATION);
        List<SensorReading> orderedReadings = readingRepository.findWindowOrdered(sessionId, contextStart, windowEnd);
        List<AiReadingSample> samples = orderedReadings.stream().map(AiReadingSample::from).toList();

        AiEvaluateWindowRequest request = new AiEvaluateWindowRequest(
                sessionId,
                windowStart,
                windowEnd,
                contextStart,
                samples
        );

        long startedNanos = System.nanoTime();
        try {
            AiEvaluateWindowResponse aiResponse = aiSidecarClient.evaluateWindow(request);
            int fallbackMs = (int) Math.max(0L, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
            int processingMs = aiResponse.processingTimeMs() != null ? aiResponse.processingTimeMs() : fallbackMs;
            String modelVersion = aiResponse.modelVersion() != null && !aiResponse.modelVersion().isBlank()
                    ? aiResponse.modelVersion()
                    : aiProperties.getModelVersion();

            return predictionService.recordPrediction(new RecordPredictionCommand(
                    sessionId,
                    modelVersion,
                    windowStart,
                    windowEnd,
                    aiResponse.predictedClass(),
                    aiResponse.confidenceScore(),
                    aiResponse.predictionStatus(),
                    aiResponse.inputQualityStatus(),
                    Instant.now(),
                    processingMs,
                    aiResponse.errorMessage()
            ));
        } catch (AiSidecarException ex) {
            int elapsedMs = (int) Math.max(0L, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
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
}
