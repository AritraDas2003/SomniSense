package com.somnisense.backend.device.service;

import com.somnisense.backend.assignment.dto.CurrentPatientInfo;
import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.audit.service.AuditAction;
import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ConflictException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.common.exception.InvalidRequestException;
import com.somnisense.backend.common.exception.ResourceNotFoundException;
import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.common.util.Strings;
import com.somnisense.backend.device.dto.DeviceResponse;
import com.somnisense.backend.device.dto.RegisterDeviceRequest;
import com.somnisense.backend.device.dto.UpdateDeviceRequest;
import com.somnisense.backend.device.entity.ConnectivityStatus;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.entity.EnrollmentStatus;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.device.repository.DeviceSpecifications;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Device registration and lifecycle (Stage 6).
 *
 * Rules:
 * - deviceCode is unique and fixed; staff may supply it (stored upper-case) or leave it blank to get "DEV-XXXXXXXX".
 * - deviceType (PHYSICAL/SIMULATED) is fixed at registration.
 * - SIMULATED devices start ENROLLED; PHYSICAL devices start PENDING.
 * - Connectivity is never set by staff; responses compute it from lastSeenAt (ConnectivityPolicy).
 * - Deactivate/reactivate is ADMIN-only (enforced in DeviceController). Devices are never deleted.
 * - Repeating an action that is already done is a no-op without an audit row.
 * - A device with an active assignment cannot be deactivated (409 DEVICE_IN_USE; release it first).
 */
@Service
public class DeviceService {

    static final int MAX_CODE_ATTEMPTS = 5;

    private static final Map<String, String> SORT_FIELDS = Map.of(
            "deviceCode", "deviceCode",
            "createdAt", "createdAt",
            "lastSeenAt", "lastSeenAt");
    private static final Set<String> DIRECTIONS = Set.of("asc", "desc");

    private final DeviceRepository deviceRepository;
    private final DeviceCodeGenerator codeGenerator;
    private final ConnectivityPolicy connectivityPolicy;
    private final AuditService auditService;
    private final DeviceAssignmentRepository assignmentRepository;
    private final PatientRepository patientRepository;

    public DeviceService(DeviceRepository deviceRepository, DeviceCodeGenerator codeGenerator,
                         ConnectivityPolicy connectivityPolicy, AuditService auditService,
                         DeviceAssignmentRepository assignmentRepository, PatientRepository patientRepository) {
        this.deviceRepository = deviceRepository;
        this.codeGenerator = codeGenerator;
        this.connectivityPolicy = connectivityPolicy;
        this.auditService = auditService;
        this.assignmentRepository = assignmentRepository;
        this.patientRepository = patientRepository;
    }

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public PageResponse<DeviceResponse> list(SourceType deviceType, EnrollmentStatus enrollment, Boolean active,
                                             ConnectivityStatus connectivity, String search,
                                             int page, int size, String sort, String direction) {
        String field = SORT_FIELDS.get(sort);
        if (field == null) {
            throw new InvalidRequestException("sort must be one of " + SORT_FIELDS.keySet());
        }
        if (!DIRECTIONS.contains(direction)) {
            throw new InvalidRequestException("direction must be 'asc' or 'desc'");
        }
        Instant now = Instant.now();   // one "now" for both the filter and the displayed status
        Sort order = Sort.by(Sort.Direction.fromString(direction), field).and(Sort.by("id"));
        Specification<Device> filter = Specification.allOf(
                DeviceSpecifications.hasType(deviceType),
                DeviceSpecifications.hasEnrollment(enrollment),
                DeviceSpecifications.isActive(active),
                DeviceSpecifications.hasConnectivity(connectivity, connectivityPolicy, now),
                DeviceSpecifications.matchesText(search));
        Page<Device> result = deviceRepository.findAll(filter, PageRequest.of(page, size, order));

        // Current assignments + patient codes for the whole page in two queries (not two per row).
        List<UUID> ids = result.getContent().stream().map(Device::getId).toList();
        Map<UUID, DeviceAssignment> byDevice = assignmentRepository.findByDeviceIdInAndReleasedAtIsNull(ids).stream()
                .collect(Collectors.toMap(DeviceAssignment::getDeviceId, Function.identity()));
        Map<UUID, String> patientCodes = patientRepository.findAllById(
                        byDevice.values().stream().map(DeviceAssignment::getPatientId).toList()).stream()
                .collect(Collectors.toMap(Patient::getId, Patient::getPatientCode));
        return PageResponse.from(result, d -> {
            DeviceAssignment a = byDevice.get(d.getId());
            CurrentPatientInfo info = a == null ? null
                    : new CurrentPatientInfo(a.getId(), a.getPatientId(), patientCodes.get(a.getPatientId()), a.getAssignedAt());
            return new DeviceResponse(d, connectivityPolicy.evaluate(d.getLastSeenAt(), now), info);
        });
    }

    @Transactional(readOnly = true)
    public DeviceResponse get(UUID id) {
        return toResponse(findOrThrow(id), Instant.now());
    }

    // ---------------------------------------------------------------- register / update

    @Transactional
    public DeviceResponse register(RegisterDeviceRequest request, UUID actorId) {
        String supplied = Strings.clean(request.getDeviceCode());
        String code;
        if (supplied != null) {
            code = supplied.toUpperCase(Locale.ROOT);
            if (deviceRepository.existsByDeviceCode(code)) {
                throw new ConflictException("Device code is already registered");
            }
        } else {
            code = newUniqueCode();
        }
        Device device = new Device(code, request.getDeviceType(), request.getModelName(),
                request.getSerialNumber(), actorId);
        deviceRepository.save(device);
        auditService.record(actorId, AuditAction.DEVICE_REGISTERED, "DEVICE", device.getId(), Map.of(
                "deviceType", device.getDeviceType().name(),
                "codeSource", supplied != null ? "STAFF" : "GENERATED"));
        return toResponse(device, Instant.now());
    }

    @Transactional
    public DeviceResponse updateDetails(UUID id, UpdateDeviceRequest request, UUID actorId) {
        Device device = findOrThrow(id);
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(device.getModelName(), Strings.clean(request.getModelName()))) {
            changed.add("modelName");
        }
        if (!Objects.equals(device.getSerialNumber(), Strings.clean(request.getSerialNumber()))) {
            changed.add("serialNumber");
        }
        if (device.updateDetails(request.getModelName(), request.getSerialNumber())) {
            auditService.record(actorId, AuditAction.DEVICE_UPDATED, "DEVICE", device.getId(), Map.of("fields", changed));
        }
        return toResponse(device, Instant.now());
    }

    // ---------------------------------------------------------------- state changes

    @Transactional
    public DeviceResponse changeEnrollment(UUID id, EnrollmentStatus status, UUID actorId) {
        Device device = findOrThrow(id);
        EnrollmentStatus old = device.getEnrollmentStatus();
        if (old != status) {
            device.changeEnrollment(status);
            auditService.record(actorId, AuditAction.DEVICE_ENROLLMENT_CHANGED, "DEVICE", device.getId(),
                    Map.of("from", old.name(), "to", status.name()));
        }
        return toResponse(device, Instant.now());
    }

    /** Refused while the device is assigned (decision 2026-11-21: release first). */
    @Transactional
    public DeviceResponse deactivate(UUID id, UUID actorId) {
        // Same row lock as AssignmentService.assign, so "assign" and "deactivate" can never interleave.
        Device device = deviceRepository.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Device", id));
        Optional<DeviceAssignment> current = assignmentRepository.findByDeviceIdAndReleasedAtIsNull(id);
        if (device.isActive() && current.isPresent()) {
            String patientCode = patientRepository.findById(current.get().getPatientId())
                    .map(Patient::getPatientCode).orElse("a patient");
            throw new ApiException(ErrorCode.DEVICE_IN_USE,
                    "Device " + device.getDeviceCode() + " is assigned to patient " + patientCode + "; release it first");
        }
        if (device.isActive()) {
            device.deactivate();
            auditService.record(actorId, AuditAction.DEVICE_DEACTIVATED, "DEVICE", device.getId(), null);
        }
        return toResponse(device, Instant.now());
    }

    @Transactional
    public DeviceResponse reactivate(UUID id, UUID actorId) {
        Device device = findOrThrow(id);
        if (!device.isActive()) {
            device.activate();
            auditService.record(actorId, AuditAction.DEVICE_REACTIVATED, "DEVICE", device.getId(), null);
        }
        return toResponse(device, Instant.now());
    }

    // ---------------------------------------------------------------- helpers

    String newUniqueCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = codeGenerator.generate();
            if (!deviceRepository.existsByDeviceCode(code)) {
                return code;
            }
        }
        throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not generate a unique device code");
    }

    private DeviceResponse toResponse(Device device, Instant now) {
        CurrentPatientInfo info = assignmentRepository.findByDeviceIdAndReleasedAtIsNull(device.getId())
                .map(a -> new CurrentPatientInfo(a.getId(), a.getPatientId(),
                        patientRepository.findById(a.getPatientId()).map(Patient::getPatientCode).orElse(null),
                        a.getAssignedAt()))
                .orElse(null);
        return new DeviceResponse(device, connectivityPolicy.evaluate(device.getLastSeenAt(), now), info);
    }

    private Device findOrThrow(UUID id) {
        return deviceRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Device", id));
    }
}
