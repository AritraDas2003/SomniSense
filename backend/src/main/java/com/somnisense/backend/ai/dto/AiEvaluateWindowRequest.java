package com.somnisense.backend.ai.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request payload sent by Spring Boot to POST /internal/ai/evaluate-window.
 */
public record AiEvaluateWindowRequest(
        UUID sessionId,
        Instant windowStart,
        Instant windowEnd,
        Instant contextStart,
        List<AiReadingSample> readings
) {
}
