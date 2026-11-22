package com.somnisense.backend.device.controller;

import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.device.dto.ChangeEnrollmentRequest;
import com.somnisense.backend.device.dto.DeviceResponse;
import com.somnisense.backend.device.dto.RegisterDeviceRequest;
import com.somnisense.backend.device.dto.UpdateDeviceRequest;
import com.somnisense.backend.device.entity.ConnectivityStatus;
import com.somnisense.backend.device.entity.EnrollmentStatus;
import com.somnisense.backend.device.service.DeviceService;
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
 * Device registration and lifecycle (Stage 6). See docs/api/DEVICES.md.
 * ADMIN and OPERATOR may register, view, edit and change enrollment; deactivate/reactivate is ADMIN-only.
 */
@RestController
@RequestMapping("/api/v1/devices")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /** GET /api/v1/devices?deviceType=&enrollmentStatus=&active=&connectivity=&q=&page=&size=&sort=&direction= */
    @GetMapping
    public PageResponse<DeviceResponse> list(@RequestParam(required = false) SourceType deviceType,
                                             @RequestParam(required = false) EnrollmentStatus enrollmentStatus,
                                             @RequestParam(required = false) Boolean active,
                                             @RequestParam(required = false) ConnectivityStatus connectivity,
                                             @RequestParam(required = false) @Size(max = 100) String q,
                                             @RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                             @RequestParam(defaultValue = "createdAt") String sort,
                                             @RequestParam(defaultValue = "desc") String direction) {
        return deviceService.list(deviceType, enrollmentStatus, active, connectivity, q, page, size, sort, direction);
    }

    @GetMapping("/{id}")
    public DeviceResponse get(@PathVariable UUID id) {
        return deviceService.get(id);
    }

    @PostMapping
    public ResponseEntity<DeviceResponse> register(@Valid @RequestBody RegisterDeviceRequest request,
                                                   @AuthenticationPrincipal AuthenticatedUser actor) {
        DeviceResponse created = deviceService.register(request, actor.getId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public DeviceResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateDeviceRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser actor) {
        return deviceService.updateDetails(id, request, actor.getId());
    }

    @PatchMapping("/{id}/enrollment")
    public DeviceResponse changeEnrollment(@PathVariable UUID id, @Valid @RequestBody ChangeEnrollmentRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser actor) {
        return deviceService.changeEnrollment(id, request.getEnrollmentStatus(), actor.getId());
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceResponse deactivate(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        return deviceService.deactivate(id, actor.getId());
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public DeviceResponse reactivate(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
        return deviceService.reactivate(id, actor.getId());
    }
}
