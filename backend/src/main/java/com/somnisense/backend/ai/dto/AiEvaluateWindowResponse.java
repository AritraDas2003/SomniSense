package com.somnisense.backend.ai.dto;

import com.somnisense.backend.prediction.entity.InputQualityStatus;
import com.somnisense.backend.prediction.entity.PredictedClass;
import com.somnisense.backend.prediction.entity.PredictionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response payload returned by the internal FastAPI Model C sidecar.
 */
public record AiEvaluateWindowResponse(
        UUID sessionId,
        String modelVersion,
        Instant windowStart,
        Instant windowEnd,
        PredictionStatus predictionStatus,
        PredictedClass predictedClass,
        BigDecimal confidenceScore,
        InputQualityStatus inputQualityStatus,
        BigDecimal decisionThreshold,
        Integer processingTimeMs,
        String errorMessage
) {
}
