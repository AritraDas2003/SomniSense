package com.somnisense.backend.session.controller;

import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.security.AuthenticatedUser;
import com.somnisense.backend.session.dto.SessionResponse;
import com.somnisense.backend.session.dto.StartSessionRequest;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.service.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** Monitoring sessions (Stage 8). ADMIN and OPERATOR. See docs/api/SESSIONS.md. */
@RestController
@RequestMapping("/api/v1/sessions")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 201 for a new session; 200 if the patient's session on this assignment was already running. */
    @PostMapping
    public ResponseEntity<SessionResponse> start(@Valid @RequestBody StartSessionRequest request,
                                                 @AuthenticationPrincipal AuthenticatedUser actor) {
        SessionService.StartResult result = sessionService.start(
                request.getPatientId(), request.getDeviceAssignmentId(), actor.getId());
        if (!result.isCreated()) {
            return ResponseEntity.ok(result.getSession());
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(result.getSession().getId()).toUri();
        return ResponseEntity.created(location).body(result.getSession());
    }

    @PostMapping("/{id}/end")
    public SessionResponse end(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        return sessionService.end(id, actor.getId());
    }

    @PostMapping("/{id}/cancel")
    public SessionResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        return sessionService.cancel(id, actor.getId());
    }

    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable UUID id) {
        return sessionService.get(id);
    }

    /** GET /api/v1/sessions?patientId=&status=&startedFrom=2026-11-21T00:00:00Z&startedTo=...&page=&size= */
    @GetMapping
    public PageResponse<SessionResponse> list(@RequestParam(required = false) UUID patientId,
                                              @RequestParam(required = false) SessionStatus status,
                                              @RequestParam(required = false) Instant startedFrom,
                                              @RequestParam(required = false) Instant startedTo,
                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return sessionService.list(patientId, status, startedFrom, startedTo, page, size);
    }
}
