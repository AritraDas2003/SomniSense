package com.somnisense.backend.prediction.service;

import com.somnisense.backend.common.exception.ConflictException;
import com.somnisense.backend.common.exception.InvalidRequestException;
import com.somnisense.backend.common.exception.ResourceNotFoundException;
import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.prediction.dto.LatestPredictionResponse;
import com.somnisense.backend.prediction.dto.PredictionResponse;
import com.somnisense.backend.prediction.dto.RecordPredictionCommand;
import com.somnisense.backend.prediction.dto.RecordPredictionResult;
import com.somnisense.backend.prediction.entity.InputQualityStatus;
import com.somnisense.backend.prediction.entity.Prediction;
import com.somnisense.backend.prediction.entity.PredictionStatus;
import com.somnisense.backend.prediction.repository.PredictionRepository;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.somnisense.backend.common.util.Strings.clean;

/**
 * Persistence and session-scoped retrieval of window-level model predictions (Stage 2).
 *
 * Validation invariants:
 * - Predictions belong to an existing non-CANCELLED monitoring session.
 * - windowStart must be strictly before windowEnd; processedAt cannot precede windowStart.
 * - Completed predictions require predictedClass and confidenceScore in [0, 1] (normalized to 6 decimals).
 * - REJECTED and ERROR predictions must never store fabricated predictedClass or confidenceScore values.
 * - Duplicate (sessionId, modelVersion, windowStart, windowEnd) writes are idempotent per V5 unique index.
 */
@Service
public class PredictionService {

    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);
    private static final int MAX_MODEL_VERSION_LENGTH = 50;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "windowEnd")
            .and(Sort.by(Sort.Direction.DESC, "processedAt"))
            .and(Sort.by(Sort.Direction.ASC, "id"));

    private final PredictionRepository predictionRepository;
    private final MonitoringSessionRepository sessionRepository;
    private final TransactionTemplate transactionTemplate;

    public PredictionService(PredictionRepository predictionRepository,
                             MonitoringSessionRepository sessionRepository,
                             PlatformTransactionManager transactionManager) {
        this.predictionRepository = predictionRepository;
        this.sessionRepository = sessionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Persists a validated prediction for internal Spring Boot orchestration (Stage 3+).
     * Idempotent on (sessionId, modelVersion, windowStart, windowEnd).
     */
    public RecordPredictionResult recordPrediction(RecordPredictionCommand command) {
        if (command == null) {
            throw new InvalidRequestException("Prediction payload is required");
        }
        UUID sessionId = command.getSessionId();
        if (sessionId == null) {
            throw new InvalidRequestException("sessionId is required");
        }
        String modelVersion = clean(command.getModelVersion());
        if (modelVersion == null || modelVersion.length() > MAX_MODEL_VERSION_LENGTH) {
            throw new InvalidRequestException("modelVersion must be 1 to 50 characters");
        }
        Instant windowStart = command.getWindowStart();
        Instant windowEnd = command.getWindowEnd();
        if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            throw new InvalidRequestException("windowStart and windowEnd are required and windowStart must be before windowEnd");
        }
        PredictionStatus status = command.getPredictionStatus();
        if (status == null) {
            throw new InvalidRequestException("predictionStatus is required");
        }
        Instant processedAt = command.getProcessedAt();
        if (processedAt == null) {
            throw new InvalidRequestException("processedAt is required");
        }
        if (processedAt.isBefore(windowStart)) {
            throw new InvalidRequestException("processedAt cannot be before windowStart");
        }
        Integer processingTimeMs = command.getProcessingTimeMs();
        if (processingTimeMs != null && processingTimeMs < 0) {
            throw new InvalidRequestException("processingTimeMs must be non-negative");
        }

        String errorMessage = clean(command.getErrorMessage());
        InputQualityStatus qualityStatus = command.getInputQualityStatus();
        BigDecimal normalizedConfidence = validateStatusPayload(command, status, qualityStatus, errorMessage);
        if (status == PredictionStatus.REJECTED && qualityStatus == null) {
            qualityStatus = InputQualityStatus.REJECTED;
        }

        MonitoringSession session = findSession(sessionId);
        if (session.getStatus() == SessionStatus.CANCELLED) {
            throw new ConflictException("Predictions cannot be recorded for a CANCELLED monitoring session");
        }

        Optional<Prediction> existing = predictionRepository
                .findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(sessionId, modelVersion, windowStart, windowEnd);
        if (existing.isPresent()) {
            log.debug("Duplicate prediction skipped for session {} model {} window [{}, {})",
                    sessionId, modelVersion, windowStart, windowEnd);
            return new RecordPredictionResult(new PredictionResponse(existing.get()), false);
        }

        Prediction candidate = new Prediction(
                sessionId,
                modelVersion,
                windowStart,
                windowEnd,
                command.getPredictedClass(),
                normalizedConfidence,
                status,
                qualityStatus,
                processedAt,
                processingTimeMs,
                errorMessage
        );

        try {
            Prediction saved = transactionTemplate.execute(tx -> predictionRepository.saveAndFlush(candidate));
            log.debug("Persisted prediction {} for session {} model {} status {}",
                    saved.getId(), sessionId, modelVersion, status);
            return new RecordPredictionResult(new PredictionResponse(saved), true);
        } catch (DataIntegrityViolationException ex) {
            Optional<Prediction> raced = predictionRepository
                    .findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(sessionId, modelVersion, windowStart, windowEnd);
            if (raced.isPresent()) {
                log.debug("Concurrent duplicate prediction resolved for session {} model {} window [{}, {})",
                        sessionId, modelVersion, windowStart, windowEnd);
                return new RecordPredictionResult(new PredictionResponse(raced.get()), false);
            }
            throw ex;
        }
    }

    /**
     * Returns paginated prediction history for a session, filtered by optional windowEnd in [from, to),
     * sorted deterministically newest-first.
     */
    @Transactional(readOnly = true)
    public PageResponse<PredictionResponse> list(UUID sessionId, Instant from, Instant to, int page, int size) {
        findSession(sessionId);
        Instant start = from != null ? from : Instant.EPOCH;
        Instant end = to != null ? to : Instant.parse("9999-01-01T00:00:00Z");
        if (!start.isBefore(end)) {
            throw new InvalidRequestException("from must be before to");
        }
        PageRequest pageable = PageRequest.of(page, size, NEWEST_FIRST);
        return PageResponse.from(predictionRepository.findWindow(sessionId, start, end, pageable), PredictionResponse::new);
    }

    /**
     * Returns the newest prediction for a session, or hasPrediction=false with latest=null when none exist.
     */
    @Transactional(readOnly = true)
    public LatestPredictionResponse latest(UUID sessionId) {
        MonitoringSession session = findSession(sessionId);
        PredictionResponse latest = predictionRepository
                .findFirstBySessionIdOrderByWindowEndDescProcessedAtDescIdDesc(sessionId)
                .map(PredictionResponse::new)
                .orElse(null);
        return new LatestPredictionResponse(session.getId(), session.getStatus().name(), latest != null, latest);
    }

    private static BigDecimal validateStatusPayload(RecordPredictionCommand command, PredictionStatus status,
                                                    InputQualityStatus qualityStatus, String errorMessage) {
        if (status == PredictionStatus.COMPLETED || status == PredictionStatus.NEEDS_REVIEW) {
            if (command.getPredictedClass() == null) {
                throw new InvalidRequestException("predictedClass is required when predictionStatus is " + status);
            }
            BigDecimal confidence = command.getConfidenceScore();
            if (confidence == null
                    || confidence.compareTo(BigDecimal.ZERO) < 0
                    || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new InvalidRequestException("confidenceScore must be between 0 and 1");
            }
            if (qualityStatus == InputQualityStatus.REJECTED) {
                throw new InvalidRequestException("predictionStatus " + status + " cannot have REJECTED inputQualityStatus");
            }
            return confidence.setScale(6, RoundingMode.HALF_UP);
        }

        if (command.getPredictedClass() != null || command.getConfidenceScore() != null) {
            throw new InvalidRequestException(status + " prediction must not contain predictedClass or confidenceScore");
        }
        if (status == PredictionStatus.REJECTED && qualityStatus == InputQualityStatus.VALID) {
            throw new InvalidRequestException("REJECTED prediction cannot have VALID inputQualityStatus");
        }
        if (status == PredictionStatus.ERROR && errorMessage == null) {
            throw new InvalidRequestException("errorMessage is required when predictionStatus is ERROR");
        }
        return null;
    }

    private MonitoringSession findSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));
    }
}
