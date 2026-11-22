package com.somnisense.backend.ingestion.controller;

import com.somnisense.backend.ingestion.dto.IngestReadingsRequest;
import com.somnisense.backend.ingestion.dto.IngestionResult;
import com.somnisense.backend.ingestion.service.IngestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Machine-to-machine reading ingestion (Stage 9). See docs/api/INGESTION.md.
 * Protected by IngestionSecurityConfig: only requests with a valid X-Ingestion-Key reach this controller;
 * staff logins (cookies) are NOT accepted here.
 */
@RestController
@RequestMapping("/api/v1/ingestion")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/readings")
    public IngestionResult ingest(@Valid @RequestBody IngestReadingsRequest request) {
        return ingestionService.ingest(request);
    }
}
