package com.somnisense.backend.device.repository;

import com.somnisense.backend.device.entity.Device;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID>, JpaSpecificationExecutor<Device> {

    boolean existsByDeviceCode(String deviceCode);

    /**
     * Loads a device and LOCKS its row (SELECT ... FOR UPDATE) until the transaction ends.
     * Assigning and deactivating both take this lock, so they can never interleave for the same device.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.id = :id")
    Optional<Device> findByIdForUpdate(@Param("id") UUID id);

    Optional<Device> findByDeviceCode(String deviceCode);

    /**
     * Records that data just arrived from the device (Stage 9). Only moves last_seen_at FORWARD, so a slow
     * request finishing late cannot make the device look older. A single UPDATE statement - no entity loading,
     * no long lock. Connectivity shown to users is still computed from last_seen_at (ConnectivityPolicy).
     */
    @Modifying
    @Query("update Device d set d.lastSeenAt = :seenAt, "
            + "d.connectivityStatus = com.somnisense.backend.device.entity.ConnectivityStatus.CONNECTED "
            + "where d.id = :id and (d.lastSeenAt is null or d.lastSeenAt < :seenAt)")
    int markSeen(@Param("id") UUID id, @Param("seenAt") Instant seenAt);
}
