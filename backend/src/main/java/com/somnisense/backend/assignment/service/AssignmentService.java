package com.somnisense.backend.assignment.service;

import com.somnisense.backend.assignment.dto.AssignmentResponse;
import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.audit.service.AuditAction;
import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.common.exception.ResourceNotFoundException;
import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.entity.EnrollmentStatus;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.entity.PatientStatus;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assigning devices to patients (Stage 7, decisions 2026-11-21).
 *
 * Rules:
 * - The device must be active and ENROLLED; the patient must be ACTIVE.
 * - Device type must equal the patient's source type (SIMULATED<->SIMULATED, PHYSICAL<->PHYSICAL).
 * - At most one active assignment per device and per patient. A conflict returns 409 unless the client sends
 *   replaceExisting=true (after the user confirmed), in which case the old assignment is released and the new
 *   one created in ONE transaction - both happen or neither does.
 * - Assigning the exact same device to the same patient again is a no-op that returns the existing assignment.
 * - History is never overwritten: release fills released_by/released_at; reassignment adds a new row.
 * - Releasing or reassigning is refused while a monitoring session is ACTIVE on that assignment
 *   (409 SESSION_ACTIVE - decision 2026-11-21: end the session first).
 *
 * Concurrency: the device row and the patient row are locked (SELECT ... FOR UPDATE) before any check, so two
 * simultaneous requests for the same device or patient run one after the other. The V2 partial unique indexes
 * are the final safety net; if they ever fire, the client gets a clear 409 instead of a 500.
 */
@Service
public class AssignmentService {

    private final DeviceAssignmentRepository assignmentRepository;
    private final DeviceRepository deviceRepository;
    private final PatientRepository patientRepository;
    private final AuditService auditService;
    private final MonitoringSessionRepository sessionRepository;

    public AssignmentService(DeviceAssignmentRepository assignmentRepository, DeviceRepository deviceRepository,
                             PatientRepository patientRepository, AuditService auditService,
                             MonitoringSessionRepository sessionRepository) {
        this.assignmentRepository = assignmentRepository;
        this.deviceRepository = deviceRepository;
        this.patientRepository = patientRepository;
        this.auditService = auditService;
        this.sessionRepository = sessionRepository;
    }

    /** Result of assign(): the assignment plus whether a NEW one was created (201) or it already existed (200). */
    public static final class AssignResult {
        private final AssignmentResponse assignment;
        private final boolean created;

        AssignResult(AssignmentResponse assignment, boolean created) {
            this.assignment = assignment;
            this.created = created;
        }

        public AssignmentResponse getAssignment() {
            return assignment;
        }

        public boolean isCreated() {
            return created;
        }
    }

    // ---------------------------------------------------------------- assign

    @Transactional
    public AssignResult assign(UUID deviceId, UUID patientId, boolean replaceExisting, UUID actorId) {
        // 1. Lock both rows first (always device, then patient - the same order everywhere avoids deadlocks).
        Device device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));
        Patient patient = patientRepository.findByIdForUpdate(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", patientId));

        // 2. Eligibility
        if (!device.isActive() || device.getEnrollmentStatus() != EnrollmentStatus.ENROLLED) {
            throw new ApiException(ErrorCode.DEVICE_NOT_AVAILABLE, "Device " + device.getDeviceCode()
                    + " must be active and ENROLLED (it is " + (device.isActive() ? "active" : "inactive")
                    + ", " + device.getEnrollmentStatus() + ")");
        }
        if (patient.getStatus() != PatientStatus.ACTIVE) {
            throw new ApiException(ErrorCode.PATIENT_NOT_ACTIVE);
        }
        if (device.getDeviceType() != patient.getSourceType()) {
            throw new ApiException(ErrorCode.SOURCE_TYPE_MISMATCH);
        }

        // 3. Existing active assignments, loaded WITH a row lock (fresh, and nobody can start a session on
        //    them or release them until we finish).
        Optional<DeviceAssignment> deviceCurrent = assignmentRepository.findActiveByDeviceIdForUpdate(deviceId);
        Optional<DeviceAssignment> patientCurrent = assignmentRepository.findActiveByPatientIdForUpdate(patientId);

        if (deviceCurrent.isPresent() && deviceCurrent.get().getPatientId().equals(patientId)) {
            return new AssignResult(new AssignmentResponse(deviceCurrent.get(), device, patient), false);   // already so
        }
        List<DeviceAssignment> conflicts = new ArrayList<>();
        deviceCurrent.ifPresent(conflicts::add);
        patientCurrent.ifPresent(conflicts::add);

        if (!conflicts.isEmpty() && !replaceExisting) {
            throw conflictError(device, patient, deviceCurrent, patientCurrent);
        }
        for (DeviceAssignment old : conflicts) {
            ensureNoActiveSession(old);   // decision 2026-11-21: end the monitoring session first
        }
        for (DeviceAssignment old : conflicts) {
            old.release(actorId);
            auditService.record(actorId, AuditAction.DEVICE_RELEASED, "ASSIGNMENT", old.getId(),
                    Map.of("reason", "REASSIGNED"));
        }
        // Hibernate normally runs INSERTs before UPDATEs; write the releases first so the unique index
        // does not see two active rows for a moment.
        assignmentRepository.flush();

        // 4. Create the new assignment
        DeviceAssignment created = new DeviceAssignment(deviceId, patientId, actorId);
        try {
            assignmentRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException raceLost) {
            // Another transaction got there first (a V2 unique index fired). The index name says which side.
            String cause = String.valueOf(raceLost.getMostSpecificCause().getMessage());
            ErrorCode code = cause.contains("ux_device_assignments_active_patient")
                    ? ErrorCode.PATIENT_ALREADY_HAS_DEVICE
                    : ErrorCode.DEVICE_ALREADY_ASSIGNED;
            throw new ApiException(code, code.getDefaultMessage()
                    + " (changed by another request at the same moment; reload and try again)");
        }
        auditService.record(actorId, AuditAction.DEVICE_ASSIGNED, "ASSIGNMENT", created.getId(), Map.of(
                "deviceId", deviceId.toString(), "patientId", patientId.toString(), "replaced", conflicts.size()));
        return new AssignResult(new AssignmentResponse(created, device, patient), true);
    }

    // ---------------------------------------------------------------- release

    @Transactional
    public AssignmentResponse release(UUID assignmentId, UUID actorId) {
        DeviceAssignment assignment = assignmentRepository.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", assignmentId));
        if (assignment.isActive()) {
            ensureNoActiveSession(assignment);
            assignment.release(actorId);
            auditService.record(actorId, AuditAction.DEVICE_RELEASED, "ASSIGNMENT", assignment.getId(),
                    Map.of("reason", "RELEASED"));
        }
        return toResponse(assignment);
    }

    /** Refuses release/reassignment while a monitoring session runs on this assignment (409 SESSION_ACTIVE). */
    private void ensureNoActiveSession(DeviceAssignment assignment) {
        sessionRepository.findByPatientIdAndStatus(assignment.getPatientId(), SessionStatus.ACTIVE)
                .filter(s -> s.getDeviceAssignmentId().equals(assignment.getId()))
                .ifPresent(s -> {
                    String patientCode = patientRepository.findById(assignment.getPatientId())
                            .map(Patient::getPatientCode).orElse("the patient");
                    throw new ApiException(ErrorCode.SESSION_ACTIVE, "Patient " + patientCode
                            + " is being monitored (session " + s.getSessionCode() + "); end the session first");
                });
    }

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public AssignmentResponse get(UUID id) {
        return toResponse(assignmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", id)));
    }

    /** History list, newest first. All filters optional. */
    @Transactional(readOnly = true)
    public PageResponse<AssignmentResponse> list(UUID deviceId, UUID patientId, Boolean active, int page, int size) {
        Specification<DeviceAssignment> filter = Specification.allOf(
                (root, q, cb) -> deviceId == null ? null : cb.equal(root.get("deviceId"), deviceId),
                (root, q, cb) -> patientId == null ? null : cb.equal(root.get("patientId"), patientId),
                (root, q, cb) -> active == null ? null
                        : (active ? cb.isNull(root.get("releasedAt")) : cb.isNotNull(root.get("releasedAt"))));
        Page<DeviceAssignment> result = assignmentRepository.findAll(filter,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "assignedAt").and(Sort.by("id"))));

        // Load the devices and patients of this page in two queries (not two per row).
        Set<UUID> deviceIds = result.getContent().stream().map(DeviceAssignment::getDeviceId).collect(Collectors.toSet());
        Set<UUID> patientIds = result.getContent().stream().map(DeviceAssignment::getPatientId).collect(Collectors.toSet());
        Map<UUID, Device> devices = deviceRepository.findAllById(deviceIds).stream()
                .collect(Collectors.toMap(Device::getId, Function.identity()));
        Map<UUID, Patient> patients = patientRepository.findAllById(patientIds).stream()
                .collect(Collectors.toMap(Patient::getId, Function.identity()));
        return PageResponse.from(result,
                a -> new AssignmentResponse(a, devices.get(a.getDeviceId()), patients.get(a.getPatientId())));
    }

    // ---------------------------------------------------------------- helpers

    private AssignmentResponse toResponse(DeviceAssignment a) {
        Device device = deviceRepository.findById(a.getDeviceId()).orElseThrow();
        Patient patient = patientRepository.findById(a.getPatientId()).orElseThrow();
        return new AssignmentResponse(a, device, patient);
    }

    /** Builds a 409 that tells the user exactly what is in the way (codes only, no personal data). */
    private ApiException conflictError(Device device, Patient patient, Optional<DeviceAssignment> deviceCurrent,
                                       Optional<DeviceAssignment> patientCurrent) {
        if (deviceCurrent.isPresent()) {
            String otherPatient = patientRepository.findById(deviceCurrent.get().getPatientId())
                    .map(Patient::getPatientCode).orElse("another patient");
            return new ApiException(ErrorCode.DEVICE_ALREADY_ASSIGNED, "Device " + device.getDeviceCode()
                    + " is already assigned to patient " + otherPatient
                    + ". Send replaceExisting=true to reassign it.");
        }
        String otherDevice = deviceRepository.findById(patientCurrent.orElseThrow().getDeviceId())
                .map(Device::getDeviceCode).orElse("another device");
        return new ApiException(ErrorCode.PATIENT_ALREADY_HAS_DEVICE, "Patient " + patient.getPatientCode()
                + " already has device " + otherDevice + ". Send replaceExisting=true to replace it.");
    }
}
