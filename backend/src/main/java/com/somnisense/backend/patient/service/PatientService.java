package com.somnisense.backend.patient.service;

import com.somnisense.backend.ai.config.AiProperties;
import com.somnisense.backend.assignment.dto.CurrentDeviceInfo;
import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.audit.service.AuditAction;
import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.common.util.Strings;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.device.service.ConnectivityPolicy;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.common.exception.InvalidRequestException;
import com.somnisense.backend.common.exception.ResourceNotFoundException;
import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.patient.dto.ContactRequest;
import com.somnisense.backend.patient.dto.CreatePatientRequest;
import com.somnisense.backend.patient.dto.MedicalDetailsRequest;
import com.somnisense.backend.patient.dto.PatientDetailResponse;
import com.somnisense.backend.patient.dto.PatientProfileFields;
import com.somnisense.backend.patient.dto.PatientSummaryResponse;
import com.somnisense.backend.patient.dto.UpdatePatientRequest;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.entity.PatientContact;
import com.somnisense.backend.patient.entity.PatientMedicalDetails;
import com.somnisense.backend.patient.entity.PatientStatus;
import com.somnisense.backend.patient.repository.PatientContactRepository;
import com.somnisense.backend.patient.repository.PatientMedicalDetailsRepository;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.patient.repository.PatientSpecifications;
import com.somnisense.backend.prediction.entity.Prediction;
import com.somnisense.backend.prediction.repository.PredictionRepository;
import com.somnisense.backend.reading.dto.LatestReadingInfo;
import com.somnisense.backend.reading.entity.SensorReading;
import com.somnisense.backend.reading.repository.SensorReadingRepository;
import com.somnisense.backend.session.dto.ActiveSessionInfo;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Patient registration and profile management (Stage 5).
 *
 * Rules:
 * - The backend generates patient codes; clients can never choose one.
 * - sourceType (PHYSICAL/SIMULATED) is fixed at registration.
 * - Contact and medical details are optional and saved in their own tables; PUT replaces them completely.
 * - Status (ACTIVE/INACTIVE) is changed only through changeStatus (ADMIN-only endpoint). Patients are never deleted.
 * - Audit rows contain field NAMES only - never personal or medical values.
 * - A patient with an assigned device cannot be set INACTIVE (409 PATIENT_HAS_ACTIVE_DEVICE; release it first).
 * - Responses include the currently assigned device and its live connectivity (Stage 7).
 */
@Service
public class PatientService {

    /** How many times to try a new random code if the generated one already exists (practically never needed). */
    static final int MAX_CODE_ATTEMPTS = 5;
    private static final LocalDate EARLIEST_BIRTH_DATE = LocalDate.of(1900, 1, 1);

    private static final Map<String, String> SORT_FIELDS = Map.of(
            "patientCode", "patientCode",
            "fullName", "fullName",
            "createdAt", "createdAt");
    private static final Set<String> DIRECTIONS = Set.of("asc", "desc");

    private final PatientRepository patientRepository;
    private final PatientContactRepository contactRepository;
    private final PatientMedicalDetailsRepository medicalRepository;
    private final PatientCodeGenerator codeGenerator;
    private final AuditService auditService;
    private final DeviceAssignmentRepository assignmentRepository;
    private final DeviceRepository deviceRepository;
    private final ConnectivityPolicy connectivityPolicy;
    private final MonitoringSessionRepository sessionRepository;
    private final SensorReadingRepository readingRepository;
    private final PredictionRepository predictionRepository;
    private final AiProperties aiProperties;

    public PatientService(PatientRepository patientRepository, PatientContactRepository contactRepository,
                          PatientMedicalDetailsRepository medicalRepository, PatientCodeGenerator codeGenerator,
                          AuditService auditService, DeviceAssignmentRepository assignmentRepository,
                          DeviceRepository deviceRepository, ConnectivityPolicy connectivityPolicy,
                          MonitoringSessionRepository sessionRepository, SensorReadingRepository readingRepository,
                          PredictionRepository predictionRepository, AiProperties aiProperties) {
        this.sessionRepository = sessionRepository;
        this.readingRepository = readingRepository;
        this.predictionRepository = predictionRepository;
        this.aiProperties = aiProperties;
        this.patientRepository = patientRepository;
        this.contactRepository = contactRepository;
        this.medicalRepository = medicalRepository;
        this.codeGenerator = codeGenerator;
        this.auditService = auditService;
        this.assignmentRepository = assignmentRepository;
        this.deviceRepository = deviceRepository;
        this.connectivityPolicy = connectivityPolicy;
    }

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public PageResponse<PatientSummaryResponse> list(SourceType sourceType, PatientStatus status, String search,
                                                     int page, int size, String sort, String direction) {
        String field = SORT_FIELDS.get(sort);
        if (field == null) {
            throw new InvalidRequestException("sort must be one of " + SORT_FIELDS.keySet());
        }
        if (!DIRECTIONS.contains(direction)) {
            throw new InvalidRequestException("direction must be 'asc' or 'desc'");
        }
        Sort order = Sort.by(Sort.Direction.fromString(direction), field).and(Sort.by("id"));
        Specification<Patient> filter = Specification.allOf(
                PatientSpecifications.hasSourceType(sourceType),
                PatientSpecifications.hasStatus(status),
                PatientSpecifications.matchesText(search));
        Page<Patient> result = patientRepository.findAll(filter, PageRequest.of(page, size, order));

        // Current device of every patient on this page: two queries in total, not two per row.
        List<UUID> ids = result.getContent().stream().map(Patient::getId).toList();
        Map<UUID, DeviceAssignment> byPatient = assignmentRepository.findByPatientIdInAndReleasedAtIsNull(ids).stream()
                .collect(Collectors.toMap(DeviceAssignment::getPatientId, Function.identity()));
        Map<UUID, Device> devices = deviceRepository.findAllById(
                        byPatient.values().stream().map(DeviceAssignment::getDeviceId).toList()).stream()
                .collect(Collectors.toMap(Device::getId, Function.identity()));
        Map<UUID, MonitoringSession> sessions = sessionRepository.findByPatientIdInAndStatus(ids, SessionStatus.ACTIVE)
                .stream().collect(Collectors.toMap(MonitoringSession::getPatientId, Function.identity()));
        // Newest reading and prediction of each active session: batch queries for the whole page.
        List<UUID> sessionIds = sessions.values().stream().map(MonitoringSession::getId).toList();
        Map<UUID, SensorReading> latestBySession = sessionIds.isEmpty() ? Map.of()
                : readingRepository.findLatestPerSession(sessionIds).stream()
                        .collect(Collectors.toMap(SensorReading::getSessionId, Function.identity()));
        Map<UUID, Prediction> latestPredictionsBySession = sessionIds.isEmpty() ? Map.of()
                : predictionRepository.findLatestPerSession(sessionIds, aiProperties.getModelVersion()).stream()
                        .collect(Collectors.toMap(Prediction::getSessionId, Function.identity()));
        Instant now = Instant.now();
        return PageResponse.from(result, p -> {
            DeviceAssignment a = byPatient.get(p.getId());
            MonitoringSession s = sessions.get(p.getId());
            SensorReading latest = s == null ? null : latestBySession.get(s.getId());
            Prediction latestPred = s == null ? null : latestPredictionsBySession.get(s.getId());
            String reviewStatus = latestPred == null ? "NONE" : latestPred.getPredictionStatus().name();
            return new PatientSummaryResponse(p,
                    a == null ? null : deviceInfo(a, devices.get(a.getDeviceId()), now),
                    sessionInfo(s),
                    latest == null ? null : new LatestReadingInfo(latest),
                    reviewStatus);
        });
    }

    @Transactional(readOnly = true)
    public PatientDetailResponse get(UUID id) {
        return detail(findOrThrow(id));
    }

    // ---------------------------------------------------------------- create

    @Transactional
    public PatientDetailResponse create(CreatePatientRequest request, UUID actorId) {
        checkBirthDate(request);
        Patient patient = new Patient(newUniqueCode(), request.getFullName(), request.getAlias(),
                request.getDateOfBirth(), request.getGender(), request.getSourceType(), actorId);
        patientRepository.save(patient);

        if (request.getContact() != null) {
            saveContact(patient, request.getContact());
        }
        if (request.getMedicalDetails() != null) {
            saveMedical(patient, request.getMedicalDetails(), actorId);
        }

        auditService.record(actorId, AuditAction.PATIENT_CREATED, "PATIENT", patient.getId(), Map.of(
                "sourceType", patient.getSourceType().name(),
                "withContact", request.getContact() != null,
                "withMedicalDetails", request.getMedicalDetails() != null));
        return detail(patient);
    }

    // ---------------------------------------------------------------- update

    @Transactional
    public PatientDetailResponse updateProfile(UUID id, UpdatePatientRequest request, UUID actorId) {
        checkBirthDate(request);
        Patient patient = findOrThrow(id);
        // Which fields change - NAMES only, so the audit row never holds personal data.
        List<String> changedFields = new ArrayList<>();
        if (!Objects.equals(patient.getFullName(), Strings.clean(request.getFullName()))) {
            changedFields.add("fullName");
        }
        if (!Objects.equals(patient.getAlias(), Strings.clean(request.getAlias()))) {
            changedFields.add("alias");
        }
        if (!Objects.equals(patient.getDateOfBirth(), request.getDateOfBirth())) {
            changedFields.add("dateOfBirth");
        }
        if (patient.getGender() != request.getGender()) {
            changedFields.add("gender");
        }
        if (patient.updateProfile(request.getFullName(), request.getAlias(),
                request.getDateOfBirth(), request.getGender())) {
            auditService.record(actorId, AuditAction.PATIENT_UPDATED, "PATIENT", patient.getId(),
                    Map.of("fields", changedFields));
        }
        return detail(patient);
    }

    @Transactional
    public PatientDetailResponse replaceContact(UUID id, ContactRequest request, UUID actorId) {
        Patient patient = findOrThrow(id);
        saveContact(patient, request);
        patient.touch();
        auditService.record(actorId, AuditAction.PATIENT_CONTACT_UPDATED, "PATIENT", patient.getId(), null);
        return detail(patient);
    }

    @Transactional
    public PatientDetailResponse replaceMedicalDetails(UUID id, MedicalDetailsRequest request, UUID actorId) {
        Patient patient = findOrThrow(id);
        saveMedical(patient, request, actorId);
        patient.touch();
        auditService.record(actorId, AuditAction.PATIENT_MEDICAL_DETAILS_UPDATED, "PATIENT", patient.getId(), null);
        return detail(patient);
    }

    @Transactional
    public PatientDetailResponse changeStatus(UUID id, PatientStatus newStatus, UUID actorId) {
        // Same row lock as AssignmentService.assign, so "assign" and "set INACTIVE" can never interleave.
        Patient patient = patientRepository.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Patient", id));
        PatientStatus old = patient.getStatus();
        if (old == PatientStatus.ACTIVE && newStatus == PatientStatus.INACTIVE) {
            Optional<DeviceAssignment> current = assignmentRepository.findByPatientIdAndReleasedAtIsNull(id);
            if (current.isPresent()) {
                String deviceCode = deviceRepository.findById(current.get().getDeviceId())
                        .map(Device::getDeviceCode).orElse("a device");
                throw new ApiException(ErrorCode.PATIENT_HAS_ACTIVE_DEVICE,
                        "Patient " + patient.getPatientCode() + " has device " + deviceCode + " assigned; release it first");
            }
        }
        if (old != newStatus) {
            patient.changeStatus(newStatus);
            auditService.record(actorId, AuditAction.PATIENT_STATUS_CHANGED, "PATIENT", patient.getId(),
                    Map.of("from", old.name(), "to", newStatus.name()));
        }
        return detail(patient);
    }

    // ---------------------------------------------------------------- helpers

    /** Generates a random code, retrying if it happens to exist already. */
    String newUniqueCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = codeGenerator.generate();
            if (!patientRepository.existsByPatientCode(code)) {
                return code;
            }
        }
        // With ~1 trillion possible codes this means something is badly wrong (e.g. a broken generator).
        throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not generate a unique patient code");
    }

    private void saveContact(Patient patient, ContactRequest r) {
        PatientContact contact = contactRepository.findByPatientId(patient.getId())
                .orElseGet(() -> new PatientContact(patient.getId()));
        contact.update(r.getPhone(), r.getEmail(), r.getAddressLine(), r.getCity(), r.getState(), r.getPostalCode(),
                r.getEmergencyContactName(), r.getEmergencyContactPhone(), r.getEmergencyContactRelationship());
        contactRepository.save(contact);
    }

    private void saveMedical(Patient patient, MedicalDetailsRequest r, UUID actorId) {
        PatientMedicalDetails medical = medicalRepository.findByPatientId(patient.getId())
                .orElseGet(() -> new PatientMedicalDetails(patient.getId()));
        medical.update(r.getMedicalHistory(), r.getSleepDisorder(), r.getMedications(), r.getNotes(), actorId);
        medicalRepository.save(medical);
    }

    private PatientDetailResponse detail(Patient patient) {
        CurrentDeviceInfo device = assignmentRepository.findByPatientIdAndReleasedAtIsNull(patient.getId())
                .flatMap(a -> deviceRepository.findById(a.getDeviceId()).map(d -> deviceInfo(a, d, Instant.now())))
                .orElse(null);
        MonitoringSession active = sessionRepository.findByPatientIdAndStatus(patient.getId(), SessionStatus.ACTIVE)
                .orElse(null);
        LatestReadingInfo latest = active == null ? null
                : readingRepository.findFirstBySessionIdOrderByMeasuredAtDesc(active.getId())
                        .map(LatestReadingInfo::new).orElse(null);
        return new PatientDetailResponse(patient,
                contactRepository.findByPatientId(patient.getId()).orElse(null),
                medicalRepository.findByPatientId(patient.getId()).orElse(null),
                device, sessionInfo(active), latest);
    }

    private static ActiveSessionInfo sessionInfo(MonitoringSession s) {
        return s == null ? null : new ActiveSessionInfo(s.getId(), s.getSessionCode(), s.getStartedAt());
    }

    private CurrentDeviceInfo deviceInfo(DeviceAssignment a, Device d, Instant now) {
        return new CurrentDeviceInfo(a.getId(), d.getId(), d.getDeviceCode(),
                connectivityPolicy.evaluate(d.getLastSeenAt(), now), d.getLastSeenAt(), a.getAssignedAt());
    }

    private Patient findOrThrow(UUID id) {
        return patientRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Patient", id));
    }

    private static void checkBirthDate(PatientProfileFields fields) {
        if (fields.getDateOfBirth() != null && fields.getDateOfBirth().isBefore(EARLIEST_BIRTH_DATE)) {
            throw new InvalidRequestException("dateOfBirth must not be before 1900-01-01");
        }
    }
}
