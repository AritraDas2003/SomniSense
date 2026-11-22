package com.somnisense.backend.assignment.controller;

import com.somnisense.backend.assignment.dto.AssignDeviceRequest;
import com.somnisense.backend.assignment.dto.AssignmentResponse;
import com.somnisense.backend.assignment.service.AssignmentService;
import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.security.AuthenticatedUser;
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
import java.util.UUID;

/** Device-to-patient assignments (Stage 7). ADMIN and OPERATOR. See docs/api/ASSIGNMENTS.md. */
@RestController
@RequestMapping("/api/v1/device-assignments")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    /** 201 for a new assignment; 200 if this device was already assigned to this patient. */
    @PostMapping
    public ResponseEntity<AssignmentResponse> assign(@Valid @RequestBody AssignDeviceRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser actor) {
        AssignmentService.AssignResult result = assignmentService.assign(
                request.getDeviceId(), request.getPatientId(), request.isReplaceExisting(), actor.getId());
        if (!result.isCreated()) {
            return ResponseEntity.ok(result.getAssignment());
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(result.getAssignment().getId()).toUri();
        return ResponseEntity.created(location).body(result.getAssignment());
    }

    @PostMapping("/{id}/release")
    public AssignmentResponse release(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        return assignmentService.release(id, actor.getId());
    }

    @GetMapping("/{id}")
    public AssignmentResponse get(@PathVariable UUID id) {
        return assignmentService.get(id);
    }

    /** GET /api/v1/device-assignments?deviceId=&patientId=&active=&page=&size= (newest first) */
    @GetMapping
    public PageResponse<AssignmentResponse> list(@RequestParam(required = false) UUID deviceId,
                                                 @RequestParam(required = false) UUID patientId,
                                                 @RequestParam(required = false) Boolean active,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return assignmentService.list(deviceId, patientId, active, page, size);
    }
}
