package com.somnisense.backend.dashboard.service;

import com.somnisense.backend.ai.config.AiProperties;
import com.somnisense.backend.assignment.dto.CurrentDeviceInfo;
import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.common.util.Strings;
import com.somnisense.backend.dashboard.dto.DashboardSummaryResponse;
import com.somnisense.backend.dashboard.dto.DashboardSummaryResponse.ActiveSessionItem;
import com.somnisense.backend.dashboard.dto.DashboardSummaryResponse.ActiveSessionStatusCounts;
import com.somnisense.backend.dashboard.dto.DashboardSummaryResponse.SimulationSummary;
import com.somnisense.backend.dashboard.dto.DashboardSummaryResponse.SummaryMetrics;
import com.somnisense.backend.device.entity.ConnectivityStatus;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.device.service.ConnectivityPolicy;
import com.somnisense.backend.patient.dto.PatientSummaryResponse;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.prediction.dto.PredictionResponse;
import com.somnisense.backend.prediction.entity.Prediction;
import com.somnisense.backend.prediction.repository.PredictionRepository;
import com.somnisense.backend.reading.dto.LatestReadingInfo;
import com.somnisense.backend.reading.entity.SensorReading;
import com.somnisense.backend.reading.repository.SensorReadingRepository;
import com.somnisense.backend.session.dto.ActiveSessionInfo;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.simulation.entity.SimulationRun;
import com.somnisense.backend.simulation.repository.SimulationRunRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only dashboard summary aggregation using fixed O(1) bounded PostgreSQL queries.
 * Never invokes ai-service, simulator-worker, or inference persistence.
 */
@Service
public class DashboardService {

    public static final int MAX_ACTIVE_SESSIONS = 50;
    public static final int RECENT_PATIENTS_LIMIT = 4;
    private static final UUID EMPTY_UUID_SENTINEL = new UUID(0L, 0L);

    private static final String SUMMARY_COUNTS_SQL = """
            WITH active_sess AS (
                SELECT id FROM monitoring_sessions WHERE status = 'ACTIVE'
            ),
            latest_pred_per_active AS (
                SELECT DISTINCT ON (p.session_id)
                    p.session_id,
                    p.prediction_status
                FROM predictions p
                JOIN active_sess s ON s.id = p.session_id
                WHERE p.model_version = :modelVersion
                ORDER BY p.session_id, p.window_end DESC, p.processed_at DESC, p.id DESC
            ),
            active_pred_stats AS (
                SELECT
                    count(*) FILTER (WHERE p.prediction_status = 'COMPLETED') AS completed_count,
                    count(*) FILTER (WHERE p.prediction_status = 'NEEDS_REVIEW') AS needs_review_window_count,
                    count(*) FILTER (WHERE p.prediction_status = 'REJECTED') AS rejected_count,
                    count(*) FILTER (WHERE p.prediction_status = 'ERROR') AS error_count,
                    count(*) FILTER (WHERE p.input_quality_status = 'DEGRADED') AS degraded_count
                FROM predictions p
                JOIN active_sess s ON s.id = p.session_id
                WHERE p.model_version = :modelVersion
            )
            SELECT
                (SELECT count(*) FROM patients) AS patient_count,
                (SELECT count(*) FROM active_sess) AS active_sessions_count,
                (SELECT count(*) FROM latest_pred_per_active WHERE prediction_status = 'NEEDS_REVIEW') AS items_needing_review,
                (SELECT count(*) FROM devices WHERE is_active = true AND last_seen_at IS NOT NULL AND last_seen_at < :staleCutoff) AS data_issues,
                coalesce(aps.completed_count, 0) AS completed_count,
                coalesce(aps.needs_review_window_count, 0) AS needs_review_window_count,
                coalesce(aps.rejected_count, 0) AS rejected_count,
                coalesce(aps.error_count, 0) AS error_count,
                coalesce(aps.degraded_count, 0) AS degraded_count,
                (SELECT count(*) FROM inference_window_jobs j JOIN active_sess s ON s.id = j.session_id
                 WHERE j.model_version = :modelVersion AND j.status = 'RETRY_WAIT') AS pending_retry_jobs
            FROM active_pred_stats aps
            """;

    private final EntityManager entityManager;
    private final PatientRepository patientRepository;
    private final DeviceAssignmentRepository assignmentRepository;
    private final DeviceRepository deviceRepository;
    private final MonitoringSessionRepository sessionRepository;
    private final SimulationRunRepository simulationRunRepository;
    private final SensorReadingRepository readingRepository;
    private final PredictionRepository predictionRepository;
    private final ConnectivityPolicy connectivityPolicy;
    private final AiProperties aiProperties;

    public DashboardService(EntityManager entityManager,
                            PatientRepository patientRepository,
                            DeviceAssignmentRepository assignmentRepository,
                            DeviceRepository deviceRepository,
                            MonitoringSessionRepository sessionRepository,
                            SimulationRunRepository simulationRunRepository,
                            SensorReadingRepository readingRepository,
                            PredictionRepository predictionRepository,
                            ConnectivityPolicy connectivityPolicy,
                            AiProperties aiProperties) {
        this.entityManager = entityManager;
        this.patientRepository = patientRepository;
        this.assignmentRepository = assignmentRepository;
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.simulationRunRepository = simulationRunRepository;
        this.readingRepository = readingRepository;
        this.predictionRepository = predictionRepository;
        this.connectivityPolicy = connectivityPolicy;
        this.aiProperties = aiProperties;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse getSummary() {
        Instant generatedAt = Instant.now();
        String modelVersion = aiProperties.getModelVersion();
        Instant staleCutoff = generatedAt.minus(connectivityPolicy.getStaleAfter());

        Object[] row = (Object[]) entityManager.createNativeQuery(SUMMARY_COUNTS_SQL)
                .setParameter("modelVersion", modelVersion)
                .setParameter("staleCutoff", staleCutoff)
                .getSingleResult();

        long patientCount = toLong(row[0]);
        long totalActiveSessions = toLong(row[1]);
        long itemsNeedingReview = toLong(row[2]);
        long dataIssues = toLong(row[3]);
        ActiveSessionStatusCounts statusCounts = new ActiveSessionStatusCounts(
                toLong(row[4]),
                toLong(row[5]),
                toLong(row[6]),
                toLong(row[7]),
                toLong(row[8]),
                toLong(row[9])
        );

        if (patientCount == 0 && totalActiveSessions == 0) {
            return new DashboardSummaryResponse(
                    generatedAt,
                    modelVersion,
                    new SummaryMetrics(0L, 0L, 0, false, 0L, dataIssues, statusCounts),
                    List.of(),
                    List.of()
            );
        }

        List<MonitoringSession> topActiveSessions = totalActiveSessions > 0
                ? sessionRepository.findActiveOrdered(PageRequest.of(0, MAX_ACTIVE_SESSIONS))
                : List.of();
        List<Patient> recentPatients = patientCount > 0
                ? patientRepository.findRecentOrdered(PageRequest.of(0, RECENT_PATIENTS_LIMIT))
                : List.of();

        Map<UUID, Patient> patientsById = new HashMap<>();
        recentPatients.forEach(p -> patientsById.put(p.getId(), p));

        Set<UUID> missingPatientIds = topActiveSessions.stream()
                .map(MonitoringSession::getPatientId)
                .filter(id -> !patientsById.containsKey(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!missingPatientIds.isEmpty()) {
            patientRepository.findAllById(missingPatientIds).forEach(p -> patientsById.put(p.getId(), p));
        }

        Map<UUID, MonitoringSession> activeSessionByPatientId = new HashMap<>();
        topActiveSessions.forEach(s -> activeSessionByPatientId.putIfAbsent(s.getPatientId(), s));

        if (totalActiveSessions > topActiveSessions.size() && !recentPatients.isEmpty()) {
            List<UUID> uncoveredRecentPatientIds = recentPatients.stream()
                    .map(Patient::getId)
                    .filter(id -> !activeSessionByPatientId.containsKey(id))
                    .toList();
            if (!uncoveredRecentPatientIds.isEmpty()) {
                sessionRepository.findByPatientIdInAndStatus(uncoveredRecentPatientIds, SessionStatus.ACTIVE)
                        .forEach(s -> activeSessionByPatientId.putIfAbsent(s.getPatientId(), s));
            }
        }

        Set<UUID> assignmentIds = topActiveSessions.stream()
                .map(MonitoringSession::getDeviceAssignmentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> recentPatientIds = recentPatients.stream()
                .map(Patient::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<UUID, DeviceAssignment> assignmentsById = new HashMap<>();
        Map<UUID, DeviceAssignment> activeAssignmentByPatientId = new HashMap<>();
        if (!assignmentIds.isEmpty() || !recentPatientIds.isEmpty()) {
            Set<UUID> safeAssignmentIds = assignmentIds.isEmpty() ? Set.of(EMPTY_UUID_SENTINEL) : assignmentIds;
            Set<UUID> safePatientIds = recentPatientIds.isEmpty() ? Set.of(EMPTY_UUID_SENTINEL) : recentPatientIds;
            for (DeviceAssignment assignment : assignmentRepository.findForDashboardBatch(safeAssignmentIds, safePatientIds)) {
                assignmentsById.put(assignment.getId(), assignment);
                if (assignment.getReleasedAt() == null) {
                    activeAssignmentByPatientId.putIfAbsent(assignment.getPatientId(), assignment);
                }
            }
        }

        Set<UUID> deviceIds = assignmentsById.values().stream()
                .map(DeviceAssignment::getDeviceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, Device> devicesById = deviceIds.isEmpty() ? Map.of()
                : deviceRepository.findAllById(deviceIds).stream()
                        .collect(Collectors.toMap(Device::getId, Function.identity()));

        List<UUID> activeSessionIds = topActiveSessions.stream().map(MonitoringSession::getId).toList();
        Map<UUID, SimulationRun> simulationBySessionId = activeSessionIds.isEmpty() ? Map.of()
                : simulationRunRepository.findBySessionIdIn(activeSessionIds).stream()
                        .collect(Collectors.toMap(SimulationRun::getSessionId, Function.identity()));

        Set<UUID> unionSessionIds = new LinkedHashSet<>(activeSessionIds);
        activeSessionByPatientId.values().forEach(s -> unionSessionIds.add(s.getId()));

        Map<UUID, SensorReading> latestReadingBySessionId = unionSessionIds.isEmpty() ? Map.of()
                : readingRepository.findLatestPerSession(unionSessionIds).stream()
                        .collect(Collectors.toMap(SensorReading::getSessionId, Function.identity()));
        Map<UUID, Prediction> latestPredictionBySessionId = unionSessionIds.isEmpty() ? Map.of()
                : predictionRepository.findLatestPerSession(unionSessionIds, modelVersion).stream()
                        .collect(Collectors.toMap(Prediction::getSessionId, Function.identity()));

        List<ActiveSessionItem> activeSessionItems = topActiveSessions.stream()
                .map(s -> toActiveSessionItem(
                        s,
                        patientsById.get(s.getPatientId()),
                        assignmentsById.get(s.getDeviceAssignmentId()),
                        devicesById,
                        simulationBySessionId.get(s.getId()),
                        latestReadingBySessionId.get(s.getId()),
                        latestPredictionBySessionId.get(s.getId()),
                        generatedAt
                ))
                .toList();

        List<PatientSummaryResponse> recentPatientResponses = recentPatients.stream()
                .map(p -> toRecentPatientSummary(
                        p,
                        activeAssignmentByPatientId.get(p.getId()),
                        devicesById,
                        activeSessionByPatientId.get(p.getId()),
                        latestReadingBySessionId,
                        latestPredictionBySessionId,
                        generatedAt
                ))
                .toList();

        SummaryMetrics metrics = new SummaryMetrics(
                patientCount,
                totalActiveSessions,
                activeSessionItems.size(),
                totalActiveSessions > activeSessionItems.size(),
                itemsNeedingReview,
                dataIssues,
                statusCounts
        );

        return new DashboardSummaryResponse(
                generatedAt,
                modelVersion,
                metrics,
                activeSessionItems,
                recentPatientResponses
        );
    }

    private ActiveSessionItem toActiveSessionItem(MonitoringSession session,
                                                  Patient patient,
                                                  DeviceAssignment assignment,
                                                  Map<UUID, Device> devicesById,
                                                  SimulationRun simulation,
                                                  SensorReading latestReading,
                                                  Prediction latestPrediction,
                                                  Instant generatedAt) {
        Device device = assignment == null ? null : devicesById.get(assignment.getDeviceId());
        String displayName = patient == null ? null
                : (Strings.clean(patient.getAlias()) != null ? patient.getAlias() : patient.getFullName());
        ConnectivityStatus connectivity = device == null
                ? ConnectivityStatus.UNKNOWN
                : connectivityPolicy.evaluate(device.getLastSeenAt(), generatedAt);

        return new ActiveSessionItem(
                session.getId(),
                session.getSessionCode(),
                session.getPatientId(),
                patient == null ? null : patient.getPatientCode(),
                displayName,
                patient == null ? null : patient.getSourceType().name(),
                device == null ? null : device.getId(),
                device == null ? null : device.getDeviceCode(),
                connectivity,
                device == null ? null : device.getLastSeenAt(),
                session.getStartedAt(),
                SimulationSummary.from(simulation),
                latestReading == null ? null : new LatestReadingInfo(latestReading),
                latestPrediction == null ? null : new PredictionResponse(latestPrediction)
        );
    }

    private PatientSummaryResponse toRecentPatientSummary(Patient patient,
                                                          DeviceAssignment assignment,
                                                          Map<UUID, Device> devicesById,
                                                          MonitoringSession activeSession,
                                                          Map<UUID, SensorReading> latestReadingBySessionId,
                                                          Map<UUID, Prediction> latestPredictionBySessionId,
                                                          Instant generatedAt) {
        CurrentDeviceInfo currentDevice = null;
        if (assignment != null) {
            Device device = devicesById.get(assignment.getDeviceId());
            if (device != null) {
                currentDevice = new CurrentDeviceInfo(
                        assignment.getId(),
                        device.getId(),
                        device.getDeviceCode(),
                        connectivityPolicy.evaluate(device.getLastSeenAt(), generatedAt),
                        device.getLastSeenAt(),
                        assignment.getAssignedAt()
                );
            }
        }
        ActiveSessionInfo sessionInfo = activeSession == null ? null
                : new ActiveSessionInfo(activeSession.getId(), activeSession.getSessionCode(), activeSession.getStartedAt());
        SensorReading latestReading = activeSession == null ? null : latestReadingBySessionId.get(activeSession.getId());
        Prediction latestPrediction = activeSession == null ? null : latestPredictionBySessionId.get(activeSession.getId());
        String reviewStatus = latestPrediction == null ? "NONE" : latestPrediction.getPredictionStatus().name();
        return new PatientSummaryResponse(
                patient,
                currentDevice,
                sessionInfo,
                latestReading == null ? null : new LatestReadingInfo(latestReading),
                reviewStatus
        );
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
