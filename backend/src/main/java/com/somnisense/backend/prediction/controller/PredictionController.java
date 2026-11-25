package com.somnisense.backend.prediction.controller;

import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.prediction.dto.LatestPredictionResponse;
import com.somnisense.backend.prediction.dto.PredictionResponse;
import com.somnisense.backend.prediction.service.PredictionService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Prediction history and latest result for a monitoring session (Stage 2). Staff only.
 * See docs/api/PREDICTIONS.md.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/predictions")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class PredictionController {

    private final PredictionService predictionService;

    public PredictionController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /**
     * GET /api/v1/sessions/{sessionId}/predictions?from=...&to=...&page=0&size=20
     * Newest-first by windowEnd DESC, processedAt DESC, id ASC. Bounded page size [1..100].
     */
    @GetMapping
    public PageResponse<PredictionResponse> list(@PathVariable UUID sessionId,
                                                 @RequestParam(required = false) Instant from,
                                                 @RequestParam(required = false) Instant to,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return predictionService.list(sessionId, from, to, page, size);
    }

    @GetMapping("/latest")
    public LatestPredictionResponse latest(@PathVariable UUID sessionId) {
        return predictionService.latest(sessionId);
    }
}
