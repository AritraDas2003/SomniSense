package com.somnisense.backend.ingestion.service;

import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.common.exception.ResourceNotFoundException;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.ingestion.dto.IngestReadingItem;
import com.somnisense.backend.ingestion.dto.IngestReadingsRequest;
import com.somnisense.backend.ingestion.dto.IngestionResult;
import com.somnisense.backend.ingestion.dto.IngestionResult.RejectReason;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.entity.SessionStatus;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import com.somnisense.backend.simulation.config.SimulationProperties;
import com.somnisense.backend.simulation.entity.SimulationRun;
import com.somnisense.backend.simulation.entity.SimulationStatus;
import com.somnisense.backend.simulation.repository.SimulationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores readings sent by the simulator / trusted gateway (Stage 9, decisions 2026-11-21).
 *
 * Routing: the sender names a DEVICE. The backend finds the device's active assignment and that assignment's
 * ACTIVE monitoring session. No active session -> 409 NO_ACTIVE_SESSION (nothing stored). The sender can never
 * choose the patient or session itself; an optional sessionCode is only CHECKED.
 *
 * Per-reading rules (a broken rule rejects only that reading - partial acceptance):
 * - readingKey required, 1-100 chars of A-Z a-z 0-9 . _ : -   (unique per device -> retries are safe)
 * - measuredAt required; at most clock-skew (10 s) in the future; not before session start minus clock-skew
 * - spo2 null or 40-100; heartRate null or 20-250 (software input guards, NOT clinical limits). Values outside
 *   are rejected, never clamped. Values are rounded to 2 decimals (the column precision).
 * - signalQuality must be null for now (its meaning is undefined - decision D-07)
 * - null values stay NULL (missing data is never turned into 0)
 *
 * Duplicates: INSERT ... ON CONFLICT DO NOTHING on the V4 unique index (device_id, reading_key). The database
 * decides atomically, so even two simultaneous retries of the same batch store each reading exactly once.
 *
 * Ingestion is high-volume, so it is not written to audit_logs (that would flood the table); counts are logged.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final Pattern READING_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    static final BigDecimal SPO2_MIN = new BigDecimal("40");
    static final BigDecimal SPO2_MAX = new BigDecimal("100");
    static final BigDecimal HR_MIN = new BigDecimal("20");
    static final BigDecimal HR_MAX = new BigDecimal("250");

    private static final String INSERT_SQL = "insert into sensor_readings "
            + "(id, session_id, device_id, reading_key, spo2, heart_rate, signal_quality, measured_at, received_at) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "on conflict (device_id, reading_key) where reading_key is not null do nothing";
    private static final String LOOKAHEAD_STEPS_SQL = "select reading_key from sensor_readings "
            + "where device_id = ? and session_id = ? and reading_key like ? and reading_key >= ? "
            + "order by reading_key asc limit 600";

    private final DeviceRepository deviceRepository;
    private final DeviceAssignmentRepository assignmentRepository;
    private final MonitoringSessionRepository sessionRepository;
    private final SimulationRunRepository simulationRunRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Duration clockSkew;
    private final Duration leaseDuration;

    @Autowired
    public IngestionService(DeviceRepository deviceRepository, DeviceAssignmentRepository assignmentRepository,
                            MonitoringSessionRepository sessionRepository, SimulationRunRepository simulationRunRepository,
                            JdbcTemplate jdbcTemplate,
                            @Value("${somnisense.ingestion.clock-skew:10s}") Duration clockSkew,
                            SimulationProperties simulationProperties) {
        this.deviceRepository = deviceRepository;
        this.assignmentRepository = assignmentRepository;
        this.sessionRepository = sessionRepository;
        this.simulationRunRepository = simulationRunRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clockSkew = clockSkew;
        this.leaseDuration = simulationProperties.getLeaseDuration();
    }

    @Transactional
    public IngestionResult ingest(IngestReadingsRequest request) {
        // 1. Route: device -> active assignment -> ACTIVE session
        String deviceCode = request.getDeviceCode().trim().toUpperCase(Locale.ROOT);
        Device device = deviceRepository.findByDeviceCode(deviceCode)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceCode));
        if (!device.isActive()) {
            throw new ApiException(ErrorCode.DEVICE_NOT_AVAILABLE, "Device " + deviceCode + " is inactive");
        }
        DeviceAssignment assignment = assignmentRepository.findByDeviceIdAndReleasedAtIsNull(device.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.NO_ACTIVE_SESSION));
        MonitoringSession session = sessionRepository.findByPatientIdAndStatus(assignment.getPatientId(), SessionStatus.ACTIVE)
                .filter(s -> s.getDeviceAssignmentId().equals(assignment.getId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NO_ACTIVE_SESSION));
        if (request.getSessionCode() != null && !request.getSessionCode().isBlank()
                && !request.getSessionCode().trim().equalsIgnoreCase(session.getSessionCode())) {
            throw new ApiException(ErrorCode.SESSION_MISMATCH);
        }

        // 1b. Atomic simulator fencing & lease check (SELECT ... FOR UPDATE before server clock check)
        SimulationRun activeSimulationRun = null;
        Instant receivedAt;
        if (device.getDeviceType() == SourceType.SIMULATED) {
            SimulationRun run = simulationRunRepository.findBySessionIdForUpdate(session.getId()).orElse(null);
            receivedAt = Instant.now();
            if (run != null) {
                if (request.getSimulationRunId() == null || request.getWorkerGeneration() == null) {
                    throw new ApiException(ErrorCode.SIMULATION_FENCING_REQUIRED);
                }
                if (!request.getSimulationRunId().equals(run.getId())) {
                    throw new ApiException(ErrorCode.SIMULATION_MISMATCH);
                }
                if (run.getStatus() != SimulationStatus.RUNNING) {
                    throw new ApiException(ErrorCode.SIMULATION_NOT_RUNNING);
                }
                if (request.getWorkerGeneration().longValue() != run.getWorkerGeneration()) {
                    throw new ApiException(ErrorCode.STALE_WORKER_GENERATION);
                }
                if (run.getLeaseExpiresAt() == null || !run.getLeaseExpiresAt().isAfter(receivedAt)) {
                    throw new ApiException(ErrorCode.SIMULATION_LEASE_EXPIRED);
                }
                activeSimulationRun = run;
            } else if (request.getSimulationRunId() != null || request.getWorkerGeneration() != null) {
                throw new ApiException(ErrorCode.SIMULATION_MISMATCH);
            }
        } else {
            if (request.getSimulationRunId() != null || request.getWorkerGeneration() != null) {
                throw new ApiException(ErrorCode.SIMULATION_DEVICE_NOT_SIMULATED);
            }
            receivedAt = Instant.now();
        }

        // 2. Validate each reading
        List<IngestionResult.Rejected> rejected = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        List<Long> rowSteps = new ArrayList<>();
        Set<String> keysInBatch = new HashSet<>();
        int duplicates = 0;
        Instant latestAllowed = receivedAt.plus(clockSkew);
        Instant earliestAllowed = session.getStartedAt().minus(clockSkew);
        OffsetDateTime receivedAtUtc = OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC);

        List<IngestReadingItem> items = request.getReadings();
        for (int i = 0; i < items.size(); i++) {
            IngestReadingItem r = items.get(i);
            if (r == null) {
                rejected.add(new IngestionResult.Rejected(i, null, RejectReason.READING_MISSING, "reading is null"));
                continue;
            }
            String key = r.getReadingKey() == null ? null : r.getReadingKey().trim();
            IngestionResult.Rejected problem = check(i, key, r, latestAllowed, earliestAllowed);
            if (problem != null) {
                rejected.add(problem);
                continue;
            }
            if (!keysInBatch.add(key)) {
                duplicates++;   // same key twice in one batch: keep the first
                continue;
            }
            rows.add(new Object[]{UUID.randomUUID(), session.getId(), device.getId(), key,
                    round(r.getSpo2()), round(r.getHeartRate()), null,
                    OffsetDateTime.ofInstant(r.getMeasuredAt(), ZoneOffset.UTC), receivedAtUtc});
            rowSteps.add(activeSimulationRun != null ? parseSimulationStep(key, deviceCode, session.getSessionCode()) : null);
        }

        // 3. Insert; rows whose key already exists for this device are skipped by the database (count = 0)
        int accepted = 0;
        Set<Long> newlyInsertedSteps = new HashSet<>();
        if (!rows.isEmpty()) {
            int[] counts = jdbcTemplate.batchUpdate(INSERT_SQL, rows);
            for (int idx = 0; idx < counts.length; idx++) {
                if (counts[idx] > 0) {
                    accepted++;
                    Long step = rowSteps.get(idx);
                    if (step != null) {
                        newlyInsertedSteps.add(step);
                    }
                } else {
                    duplicates++;
                }
            }
        }

        // 3b. Advance contiguous last_step only from verified persisted readings and renew lease
        if (activeSimulationRun != null) {
            long nextStep = activeSimulationRun.getLastStep() + 1L;
            while (newlyInsertedSteps.contains(nextStep)) {
                nextStep++;
            }
            if (nextStep > activeSimulationRun.getLastStep() + 1L || duplicates > 0) {
                String prefix = deviceCode + "-" + session.getSessionCode() + "-";
                String minKey = String.format(Locale.ROOT, "%s%08d", prefix, nextStep);
                List<String> existingKeys = jdbcTemplate.queryForList(
                        LOOKAHEAD_STEPS_SQL,
                        String.class,
                        device.getId(),
                        session.getId(),
                        prefix + "%",
                        minKey
                );
                Set<Long> persistedSteps = new HashSet<>(newlyInsertedSteps);
                for (String existingKey : existingKeys) {
                    Long existingStep = parseSimulationStep(existingKey, deviceCode, session.getSessionCode());
                    if (existingStep != null) {
                        persistedSteps.add(existingStep);
                    }
                }
                while (persistedSteps.contains(nextStep)) {
                    nextStep++;
                }
            }
            long newLastStep = nextStep - 1L;
            activeSimulationRun.renewLeaseAndAdvanceStep(
                    newLastStep,
                    request.getWorkerInstanceId(),
                    receivedAt.plus(leaseDuration),
                    receivedAt
            );
            simulationRunRepository.save(activeSimulationRun);
        }

        // 4. Data is arriving: move last_seen_at forward (drives CONNECTED/STALE/DISCONNECTED)
        if (accepted + duplicates > 0) {
            deviceRepository.markSeen(device.getId(), receivedAt);
        }
        log.debug("Ingested batch for device {} session {}: accepted={}, duplicates={}, rejected={}",
                deviceCode, session.getSessionCode(), accepted, duplicates, rejected.size());
        return new IngestionResult(deviceCode, session.getId(), session.getSessionCode(), receivedAt,
                accepted, duplicates, rejected);
    }

    private static Long parseSimulationStep(String key, String deviceCode, String sessionCode) {
        String expectedPrefix = (deviceCode + "-" + sessionCode + "-").toUpperCase(Locale.ROOT);
        if (!key.toUpperCase(Locale.ROOT).startsWith(expectedPrefix)) {
            return null;
        }
        String suffix = key.substring(expectedPrefix.length());
        if (suffix.isEmpty() || suffix.length() > 15) {
            return null;
        }
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        long step = Long.parseLong(suffix);
        return step >= 1L ? step : null;
    }

    /** Returns null if the reading is valid, otherwise the reason it is rejected. */
    private static IngestionResult.Rejected check(int i, String key, IngestReadingItem r, Instant latestAllowed,
                                                  Instant earliestAllowed) {
        if (key == null || !READING_KEY.matcher(key).matches()) {
            return new IngestionResult.Rejected(i, key, RejectReason.INVALID_READING_KEY,
                    "readingKey is required: 1-100 characters of A-Z a-z 0-9 . _ : -");
        }
        if (r.getMeasuredAt() == null) {
            return new IngestionResult.Rejected(i, key, RejectReason.MISSING_MEASURED_AT, "measuredAt is required");
        }
        if (r.getMeasuredAt().isAfter(latestAllowed)) {
            return new IngestionResult.Rejected(i, key, RejectReason.MEASURED_AT_IN_FUTURE,
                    "measuredAt is in the future (check the device clock)");
        }
        if (r.getMeasuredAt().isBefore(earliestAllowed)) {
            return new IngestionResult.Rejected(i, key, RejectReason.MEASURED_AT_BEFORE_SESSION,
                    "measuredAt is before the monitoring session started");
        }
        if (outside(r.getSpo2(), SPO2_MIN, SPO2_MAX)) {
            return new IngestionResult.Rejected(i, key, RejectReason.SPO2_OUT_OF_RANGE, "spo2 must be between 40 and 100");
        }
        if (outside(r.getHeartRate(), HR_MIN, HR_MAX)) {
            return new IngestionResult.Rejected(i, key, RejectReason.HEART_RATE_OUT_OF_RANGE,
                    "heartRate must be between 20 and 250");
        }
        if (r.getSignalQuality() != null) {
            return new IngestionResult.Rejected(i, key, RejectReason.SIGNAL_QUALITY_NOT_SUPPORTED,
                    "signalQuality must be null until its meaning is defined (D-07)");
        }
        return null;
    }

    private static boolean outside(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value != null && (value.compareTo(min) < 0 || value.compareTo(max) > 0);
    }

    private static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
