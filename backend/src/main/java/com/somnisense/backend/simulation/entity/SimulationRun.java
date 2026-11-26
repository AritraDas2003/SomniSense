package com.somnisense.backend.simulation.entity;

import com.somnisense.backend.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static com.somnisense.backend.common.util.Strings.clean;

/**
 * Durable metadata, checkpoint, and fencing lease for one simulated monitoring session.
 * Maps 1:1 to V5 simulation_runs + V6 fencing/lease columns.
 */
@Entity
@Table(name = "simulation_runs")
@DynamicUpdate
public class SimulationRun extends AssignedIdEntity {

    @Column(name = "session_id", nullable = false, unique = true, updatable = false)
    private UUID sessionId;

    @Column(name = "scenario_id", nullable = false, length = 60)
    private String scenarioId;

    @Column(name = "seed", nullable = false)
    private long seed;

    @Column(name = "cadence_hz", nullable = false, precision = 6, scale = 2)
    private BigDecimal cadenceHz;

    @Column(name = "playback_speed", nullable = false, precision = 6, scale = 2)
    private BigDecimal playbackSpeed;

    @Column(name = "quality_preset", length = 60)
    private String qualityPreset;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SimulationStatus status;

    @Column(name = "last_step", nullable = false)
    private long lastStep;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "worker_generation", nullable = false)
    private long workerGeneration;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "worker_instance_id", length = 100)
    private String workerInstanceId;

    protected SimulationRun() {
    }

    public SimulationRun(UUID sessionId, String scenarioId, long seed, BigDecimal cadenceHz,
                         BigDecimal playbackSpeed, String qualityPreset, SimulationStatus status,
                         long lastStep, long workerGeneration, Instant leaseExpiresAt,
                         String workerInstanceId, Instant startedAt) {
        super(UUID.randomUUID());
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.scenarioId = Objects.requireNonNull(scenarioId, "scenarioId").trim();
        this.seed = seed;
        this.cadenceHz = Objects.requireNonNull(cadenceHz, "cadenceHz");
        this.playbackSpeed = Objects.requireNonNull(playbackSpeed, "playbackSpeed");
        this.qualityPreset = clean(qualityPreset);
        this.status = Objects.requireNonNull(status, "status");
        this.lastStep = lastStep;
        this.workerGeneration = workerGeneration;
        this.leaseExpiresAt = leaseExpiresAt;
        this.workerInstanceId = clean(workerInstanceId);
        Instant now = Objects.requireNonNull(startedAt, "startedAt");
        this.startedAt = now;
        this.updatedAt = now;
    }

    /** Transitions run to RUNNING under a fresh monotonic workerGeneration and lease deadline. */
    public void startNextGeneration(Instant now, Instant newLeaseExpiresAt) {
        this.workerGeneration += 1L;
        this.status = SimulationStatus.RUNNING;
        this.leaseExpiresAt = Objects.requireNonNull(newLeaseExpiresAt, "newLeaseExpiresAt");
        this.workerInstanceId = null;
        this.updatedAt = clampUpdatedAt(now);
    }

    public void attachWorkerInstance(String workerInstanceId, Instant now) {
        this.workerInstanceId = clean(workerInstanceId);
        this.updatedAt = clampUpdatedAt(now);
    }

    public void renewLeaseAndAdvanceStep(long newLastStep, String workerInstanceId,
                                         Instant newLeaseExpiresAt, Instant now) {
        if (newLastStep > this.lastStep) {
            this.lastStep = newLastStep;
        }
        String cleanedWorker = clean(workerInstanceId);
        if (cleanedWorker != null) {
            this.workerInstanceId = cleanedWorker;
        }
        this.leaseExpiresAt = Objects.requireNonNull(newLeaseExpiresAt, "newLeaseExpiresAt");
        this.updatedAt = clampUpdatedAt(now);
    }

    public void pause(Instant now) {
        this.status = SimulationStatus.PAUSED;
        this.leaseExpiresAt = null;
        this.workerInstanceId = null;
        this.updatedAt = clampUpdatedAt(now);
    }

    public void stop(Instant now) {
        this.status = SimulationStatus.STOPPED;
        this.leaseExpiresAt = null;
        this.workerInstanceId = null;
        this.updatedAt = clampUpdatedAt(now);
    }

    public void fail(Instant now) {
        this.status = SimulationStatus.FAILED;
        this.leaseExpiresAt = null;
        this.workerInstanceId = null;
        this.updatedAt = clampUpdatedAt(now);
    }

    private Instant clampUpdatedAt(Instant now) {
        Instant candidate = Objects.requireNonNull(now, "now");
        return candidate.isBefore(this.startedAt) ? this.startedAt : candidate;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public long getSeed() {
        return seed;
    }

    public BigDecimal getCadenceHz() {
        return cadenceHz;
    }

    public BigDecimal getPlaybackSpeed() {
        return playbackSpeed;
    }

    public String getQualityPreset() {
        return qualityPreset;
    }

    public SimulationStatus getStatus() {
        return status;
    }

    public long getLastStep() {
        return lastStep;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getWorkerGeneration() {
        return workerGeneration;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String getWorkerInstanceId() {
        return workerInstanceId;
    }
}
