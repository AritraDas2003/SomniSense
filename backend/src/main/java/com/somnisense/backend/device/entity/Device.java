package com.somnisense.backend.device.entity;

import com.somnisense.backend.common.domain.SourceType;
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

import static com.somnisense.backend.common.util.Strings.clean;

/**
 * A pulse-oximeter device, physical or simulated. Maps 1:1 to the approved "devices" table.
 *
 * Three SEPARATE states (master prompt 6.3):
 * - active            : is the device in service? (Admin deactivates/reactivates)
 * - enrollmentStatus  : has it been set up? (staff update)
 * - connectivityStatus: is data arriving? (follows lastSeenAt, never set by staff)
 *
 * deviceCode and deviceType are fixed after registration. No device secrets are stored here: a device ID alone
 * never proves who is sending data (ingestion uses a separate trusted key - decision F8).
 */
@Entity
@Table(name = "devices")
@DynamicUpdate
public class Device extends AssignedIdEntity {

    @Column(name = "device_code", nullable = false, unique = true, length = 50, updatable = false)
    private String deviceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 20, updatable = false)
    private SourceType deviceType;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "connectivity_status", nullable = false, length = 20)
    private ConnectivityStatus connectivityStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "enrollment_status", nullable = false, length = 20)
    private EnrollmentStatus enrollmentStatus;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "registered_by", nullable = false, updatable = false)
    private UUID registeredBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Device() {
    }

    /** New devices are active and have never been seen. SIMULATED devices are enrolled immediately. */
    public Device(String deviceCode, SourceType deviceType, String modelName, String serialNumber, UUID registeredBy) {
        super(UUID.randomUUID());
        this.deviceCode = Objects.requireNonNull(deviceCode, "deviceCode");
        this.deviceType = Objects.requireNonNull(deviceType, "deviceType");
        this.modelName = clean(modelName);
        this.serialNumber = clean(serialNumber);
        this.connectivityStatus = ConnectivityStatus.UNKNOWN;
        this.enrollmentStatus = deviceType == SourceType.SIMULATED ? EnrollmentStatus.ENROLLED : EnrollmentStatus.PENDING;
        this.active = true;
        this.registeredBy = Objects.requireNonNull(registeredBy, "registeredBy");
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Replaces model name and serial number. Returns true if anything changed. */
    public boolean updateDetails(String modelName, String serialNumber) {
        String newModel = clean(modelName);
        String newSerial = clean(serialNumber);
        if (Objects.equals(newModel, this.modelName) && Objects.equals(newSerial, this.serialNumber)) {
            return false;
        }
        this.modelName = newModel;
        this.serialNumber = newSerial;
        touch();
        return true;
    }

    public void changeEnrollment(EnrollmentStatus status) {
        this.enrollmentStatus = Objects.requireNonNull(status, "enrollmentStatus");
        touch();
    }

    public void deactivate() {
        this.active = false;
        touch();
    }

    public void activate() {
        this.active = true;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public SourceType getDeviceType() {
        return deviceType;
    }

    public String getModelName() {
        return modelName;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    /** The value last STORED in the column. APIs should use ConnectivityPolicy, which is always current. */
    public ConnectivityStatus getStoredConnectivityStatus() {
        return connectivityStatus;
    }

    public EnrollmentStatus getEnrollmentStatus() {
        return enrollmentStatus;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public UUID getRegisteredBy() {
        return registeredBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
