package com.somnisense.backend.reading.controller;

import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.reading.dto.LatestReadingsResponse;
import com.somnisense.backend.reading.dto.ReadingResponse;
import com.somnisense.backend.reading.service.ReadingQueryService;
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

/** Reading history and latest values of a monitoring session (Stage 9). Staff only. See docs/api/READINGS.md. */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/readings")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class ReadingController {

    private final ReadingQueryService readingQueryService;

    public ReadingController(ReadingQueryService readingQueryService) {
        this.readingQueryService = readingQueryService;
    }

    /** GET .../readings?from=2026-11-21T14:00:00Z&to=...&page=0&size=300  (oldest first, max 1000 per page) */
    @GetMapping
    public PageResponse<ReadingResponse> list(@PathVariable UUID sessionId,
                                              @RequestParam(required = false) Instant from,
                                              @RequestParam(required = false) Instant to,
                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = "300") @Min(1) @Max(1000) int size) {
        return readingQueryService.list(sessionId, from, to, page, size);
    }

    @GetMapping("/latest")
    public LatestReadingsResponse latest(@PathVariable UUID sessionId) {
        return readingQueryService.latest(sessionId);
    }
}
