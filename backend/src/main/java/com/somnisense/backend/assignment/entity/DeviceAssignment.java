package com.somnisense.backend.assignment.entity;

import com.somnisense.backend.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * "Device X was given to patient Y" - one row of the approved "device_assignments" table.
 *
 * An assignment is ACTIVE while releasedAt is null. Releasing never deletes or overwrites history: it only
 * fills releasedBy/releasedAt, and a reassignment creates a NEW row (master prompt 6.3).
 * At most one active row per device and per patient (Java checks + V2 partial unique indexes).
 *
 * deviceId/patientId/assignedBy are plain UUID fields (no JPA relationships, no cascades); the database
 * foreign keys guarantee they point at real rows.
 */
@Entity
@Table(name = "device_assignments")
@DynamicUpdate
public class DeviceAssignment extends AssignedIdEntity {

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "assigned_by", nullable = false, updatable = false)
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "released_by")
    private UUID releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected DeviceAssignment() {
    }

    public DeviceAssignment(UUID deviceId, UUID patientId, UUID assignedBy) {
        super(UUID.randomUUID());
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.patientId = Objects.requireNonNull(patientId, "patientId");
        this.assignedBy = Objects.requireNonNull(assignedBy, "assignedBy");
        this.assignedAt = Instant.now();
    }

    public boolean isActive() {
        return releasedAt == null;
    }

    /** Ends the assignment. Has no effect if it was already released. */
    public void release(UUID releasedBy) {
        if (isActive()) {
            this.releasedBy = Objects.requireNonNull(releasedBy, "releasedBy");
            this.releasedAt = Instant.now();
        }
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public UUID getAssignedBy() {
        return assignedBy;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public UUID getReleasedBy() {
        return releasedBy;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
