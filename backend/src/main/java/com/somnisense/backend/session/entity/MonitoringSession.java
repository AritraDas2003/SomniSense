package com.somnisense.backend.session.entity;

import com.somnisense.backend.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One monitoring session of a patient using an assigned device (approved "monitoring_sessions" table).
 *
 * (Not to be confused with the HTTP login session - this is a period of patient monitoring.)
 *
 * - startedBy/endedBy always come from the logged-in staff member, never from the request body.
 * - startedAt/endedAt use the SERVER clock (UTC).
 * - Only ACTIVE -> ENDED and ACTIVE -> CANCELLED are possible; finish() refuses anything else.
 */
@Entity
@Table(name = "monitoring_sessions")
@DynamicUpdate
public class MonitoringSession extends AssignedIdEntity {

    @Column(name = "session_code", nullable = false, unique = true, length = 40, updatable = false)
    private String sessionCode;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "device_assignment_id", nullable = false, updatable = false)
    private UUID deviceAssignmentId;

    @Column(name = "started_by", nullable = false, updatable = false)
    private UUID startedBy;

    @Column(name = "ended_by")
    private UUID endedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SessionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MonitoringSession() {
    }

    public MonitoringSession(String sessionCode, UUID patientId, UUID deviceAssignmentId, UUID startedBy,
                             Instant startedAt) {
        super(UUID.randomUUID());
        this.sessionCode = Objects.requireNonNull(sessionCode, "sessionCode");
        this.patientId = Objects.requireNonNull(patientId, "patientId");
        this.deviceAssignmentId = Objects.requireNonNull(deviceAssignmentId, "deviceAssignmentId");
        this.startedBy = Objects.requireNonNull(startedBy, "startedBy");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.status = SessionStatus.ACTIVE;
        this.createdAt = startedAt;
    }

    public boolean isActive() {
        return status == SessionStatus.ACTIVE;
    }

    /** ACTIVE -> ENDED or CANCELLED. Callers must check isActive() first; anything else is a programming error. */
    public void finish(SessionStatus finalStatus, UUID by) {
        if (!isActive() || finalStatus == SessionStatus.ACTIVE) {
            throw new IllegalStateException("Cannot change session " + sessionCode + " from " + status + " to " + finalStatus);
        }
        this.status = finalStatus;
        this.endedBy = Objects.requireNonNull(by, "endedBy");
        this.endedAt = Instant.now();
    }

    public String getSessionCode() {
        return sessionCode;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public UUID getDeviceAssignmentId() {
        return deviceAssignmentId;
    }

    public UUID getStartedBy() {
        return startedBy;
    }

    public UUID getEndedBy() {
        return endedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
