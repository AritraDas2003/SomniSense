package com.somnisense.backend.device.repository;

import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.device.entity.ConnectivityStatus;
import com.somnisense.backend.device.entity.Device;
import com.somnisense.backend.device.entity.EnrollmentStatus;
import com.somnisense.backend.device.service.ConnectivityPolicy;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Locale;

/** Optional filters for the device list (UI plan C40: search code, filter source/status). */
public final class DeviceSpecifications {

    private DeviceSpecifications() {
    }

    public static Specification<Device> hasType(SourceType type) {
        return (root, query, cb) -> type == null ? null : cb.equal(root.get("deviceType"), type);
    }

    public static Specification<Device> hasEnrollment(EnrollmentStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("enrollmentStatus"), status);
    }

    public static Specification<Device> isActive(Boolean active) {
        return (root, query, cb) -> active == null ? null : cb.equal(root.get("active"), active);
    }

    /**
     * Filters by the COMPUTED connectivity, translated into last_seen_at time ranges with the same thresholds
     * as ConnectivityPolicy (so list filter and displayed status always agree).
     */
    public static Specification<Device> hasConnectivity(ConnectivityStatus status, ConnectivityPolicy policy,
                                                        Instant now) {
        return (root, query, cb) -> {
            if (status == null) {
                return null;
            }
            Instant staleFrom = now.minus(policy.getStaleAfter());
            Instant disconnectedFrom = now.minus(policy.getDisconnectedAfter());
            var lastSeen = root.<Instant>get("lastSeenAt");
            return switch (status) {
                case UNKNOWN -> cb.isNull(lastSeen);
                case CONNECTED -> cb.greaterThanOrEqualTo(lastSeen, staleFrom);
                case STALE -> cb.and(cb.lessThan(lastSeen, staleFrom), cb.greaterThanOrEqualTo(lastSeen, disconnectedFrom));
                case DISCONNECTED -> cb.lessThan(lastSeen, disconnectedFrom);
            };
        };
    }

    /** Case-insensitive "contains" on device code, model name or serial number. */
    public static Specification<Device> matchesText(String text) {
        return (root, query, cb) -> {
            if (text == null || text.isBlank()) {
                return null;
            }
            String escaped = text.trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            String pattern = "%" + escaped + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("deviceCode")), pattern, '\\'),
                    cb.like(cb.lower(root.get("modelName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("serialNumber")), pattern, '\\'));
        };
    }
}
