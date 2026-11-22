package com.somnisense.backend.patient.controller;

import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.patient.dto.ChangePatientStatusRequest;
import com.somnisense.backend.patient.dto.ContactRequest;
import com.somnisense.backend.patient.dto.CreatePatientRequest;
import com.somnisense.backend.patient.dto.MedicalDetailsRequest;
import com.somnisense.backend.patient.dto.PatientDetailResponse;
import com.somnisense.backend.patient.dto.PatientSummaryResponse;
import com.somnisense.backend.patient.dto.UpdatePatientRequest;
import com.somnisense.backend.patient.entity.PatientStatus;
import com.somnisense.backend.patient.service.PatientService;
import com.somnisense.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Patient registration and profiles (Stage 5). See docs/api/PATIENTS.md.
 * ADMIN and OPERATOR may use everything here except changing status, which is ADMIN-only (decision 2026-11-21).
 */
@RestController
@RequestMapping("/api/v1/patients")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    /** GET /api/v1/patients?sourceType=SIMULATED&status=ACTIVE&q=pt-7k&page=0&size=20&sort=createdAt&direction=desc */
    @GetMapping
    public PageResponse<PatientSummaryResponse> list(@RequestParam(required = false) SourceType sourceType,
                                                     @RequestParam(required = false) PatientStatus status,
                                                     @RequestParam(required = false) @Size(max = 100) String q,
                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                     @RequestParam(defaultValue = "createdAt") String sort,
                                                     @RequestParam(defaultValue = "desc") String direction) {
        return patientService.list(sourceType, status, q, page, size, sort, direction);
    }

    @GetMapping("/{id}")
    public PatientDetailResponse get(@PathVariable UUID id) {
        return patientService.get(id);
    }

    /** 201 Created + Location header; the response contains the backend-generated patientCode. */
    @PostMapping
    public ResponseEntity<PatientDetailResponse> create(@Valid @RequestBody CreatePatientRequest request,
                                                        @AuthenticationPrincipal AuthenticatedUser actor) {
        PatientDetailResponse created = patientService.create(request, actor.getId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Replaces fullName, alias, dateOfBirth, gender. */
    @PutMapping("/{id}")
    public PatientDetailResponse update(@PathVariable UUID id, @Valid @RequestBody UpdatePatientRequest request,
                                        @AuthenticationPrincipal AuthenticatedUser actor) {
        return patientService.updateProfile(id, request, actor.getId());
    }

    /** Creates or replaces the contact block. */
    @PutMapping("/{id}/contact")
    public PatientDetailResponse replaceContact(@PathVariable UUID id, @Valid @RequestBody ContactRequest request,
                                                @AuthenticationPrincipal AuthenticatedUser actor) {
        return patientService.replaceContact(id, request, actor.getId());
    }

    /** Creates or replaces the medical details block. */
    @PutMapping("/{id}/medical-details")
    public PatientDetailResponse replaceMedicalDetails(@PathVariable UUID id,
                                                       @Valid @RequestBody MedicalDetailsRequest request,
                                                       @AuthenticationPrincipal AuthenticatedUser actor) {
        return patientService.replaceMedicalDetails(id, request, actor.getId());
    }

    /** ADMIN only: ACTIVE <-> INACTIVE. (Method-level @PreAuthorize narrows the class-level rule.) */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public PatientDetailResponse changeStatus(@PathVariable UUID id,
                                              @Valid @RequestBody ChangePatientStatusRequest request,
                                              @AuthenticationPrincipal AuthenticatedUser actor) {
        return patientService.changeStatus(id, request.getStatus(), actor.getId());
    }
}
