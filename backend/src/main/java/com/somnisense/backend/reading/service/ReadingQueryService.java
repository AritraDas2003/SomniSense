package com.somnisense.backend.reading.service;

import com.somnisense.backend.assignment.entity.DeviceAssignment;
import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.common.exception.InvalidRequestException;
import com.somnisense.backend.common.exception.ResourceNotFoundException;
import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.device.service.ConnectivityPolicy;
import com.somnisense.backend.reading.dto.LatestReadingsResponse;
import com.somnisense.backend.reading.dto.LatestValue;
import com.somnisense.backend.reading.dto.ReadingResponse;
import com.somnisense.backend.reading.repository.SensorReadingRepository;
import com.somnisense.backend.session.entity.MonitoringSession;
import com.somnisense.backend.session.repository.MonitoringSessionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Reading history and latest values for a monitoring session (Stage 9).
 * History is ALWAYS bounded: paged (max 1000 per page) and optionally limited to a time window.
 */
@Service
public class ReadingQueryService {

    private final SensorReadingRepository readingRepository;
    private final MonitoringSessionRepository sessionRepository;
    private final DeviceAssignmentRepository assignmentRepository;
    private final DeviceRepository deviceRepository;
    private final ConnectivityPolicy connectivityPolicy;

    public ReadingQueryService(SensorReadingRepository readingRepository, MonitoringSessionRepository sessionRepository,
                               DeviceAssignmentRepository assignmentRepository, DeviceRepository deviceRepository,
                               ConnectivityPolicy connectivityPolicy) {
        this.readingRepository = readingRepository;
        this.sessionRepository = sessionRepository;
        this.assignmentRepository = assignmentRepository;
        this.deviceRepository = deviceRepository;
        this.connectivityPolicy = connectivityPolicy;
    }

    /** Readings with from <= measuredAt < to, oldest first. from/to are optional. */
    @Transactional(readOnly = true)
    public PageResponse<ReadingResponse> list(UUID sessionId, Instant from, Instant to, int page, int size) {
        findSession(sessionId);
        Instant start = from != null ? from : Instant.EPOCH;
        Instant end = to != null ? to : Instant.parse("9999-01-01T00:00:00Z");
        if (!start.isBefore(end)) {
            throw new InvalidRequestException("from must be before to");
        }
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.ASC, "measuredAt").and(Sort.by("id")));
        return PageResponse.from(readingRepository.findWindow(sessionId, start, end, pageable), ReadingResponse::new);
    }

    @Transactional(readOnly = true)
    public LatestReadingsResponse latest(UUID sessionId) {
        MonitoringSession session = findSession(sessionId);
        DeviceAssignment assignment = assignmentRepository.findById(session.getDeviceAssignmentId()).orElseThrow();
        Device device = deviceRepository.findById(assignment.getDeviceId()).orElseThrow();

        ReadingResponse latest = readingRepository.findFirstBySessionIdOrderByMeasuredAtDesc(sessionId)
                .map(ReadingResponse::new).orElse(null);
        LatestValue lastSpo2 = readingRepository.findFirstBySessionIdAndSpo2IsNotNullOrderByMeasuredAtDesc(sessionId)
                .map(r -> new LatestValue(r.getSpo2(), r.getMeasuredAt())).orElse(null);
        LatestValue lastHr = readingRepository.findFirstBySessionIdAndHeartRateIsNotNullOrderByMeasuredAtDesc(sessionId)
                .map(r -> new LatestValue(r.getHeartRate(), r.getMeasuredAt())).orElse(null);

        return new LatestReadingsResponse(session.getId(), session.getStatus().name(), device.getDeviceType().name(),
                connectivityPolicy.evaluate(device.getLastSeenAt(), Instant.now()).name(), device.getLastSeenAt(),
                latest, lastSpo2, lastHr);
    }

    private MonitoringSession findSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));
    }
}
