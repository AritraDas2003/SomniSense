package com.somnisense.backend.assignment.repository;

import com.somnisense.backend.assignment.entity.DeviceAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * "Active" = releasedAt is null. Method names are turned into SQL by Spring Data, e.g.
 * findByDeviceIdAndReleasedAtIsNull -> ... where device_id = ? and released_at is null
 * (these lookups use the V2 partial unique indexes).
 */
public interface DeviceAssignmentRepository extends JpaRepository<DeviceAssignment, UUID>,
        JpaSpecificationExecutor<DeviceAssignment> {

    /** Loads one assignment and locks its row until the transaction ends (prevents a double release). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DeviceAssignment a where a.id = :id")
    Optional<DeviceAssignment> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The device's active assignment, loaded AND locked in one query. If another transaction is releasing it,
     * this waits; PostgreSQL then re-checks "released_at is null", so the answer is always current.
     * (Loading first and locking afterwards would keep a possibly stale copy in memory.)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DeviceAssignment a where a.deviceId = :deviceId and a.releasedAt is null")
    Optional<DeviceAssignment> findActiveByDeviceIdForUpdate(@Param("deviceId") UUID deviceId);

    /** Same as above for the patient's active assignment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DeviceAssignment a where a.patientId = :patientId and a.releasedAt is null")
    Optional<DeviceAssignment> findActiveByPatientIdForUpdate(@Param("patientId") UUID patientId);

    Optional<DeviceAssignment> findByDeviceIdAndReleasedAtIsNull(UUID deviceId);

    Optional<DeviceAssignment> findByPatientIdAndReleasedAtIsNull(UUID patientId);

    /** Active assignments for a page of patients (dashboard list) in one query. */
    List<DeviceAssignment> findByPatientIdInAndReleasedAtIsNull(Collection<UUID> patientIds);

    /** Active assignments for a page of devices (inventory list) in one query. */
    List<DeviceAssignment> findByDeviceIdInAndReleasedAtIsNull(Collection<UUID> deviceIds);

    /** Batch-loads session assignments plus active assignments for dashboard patients in one query. */
    @Query("select a from DeviceAssignment a where a.id in (:assignmentIds) "
            + "or (a.patientId in (:patientIds) and a.releasedAt is null)")
    List<DeviceAssignment> findForDashboardBatch(@Param("assignmentIds") Collection<UUID> assignmentIds,
                                                 @Param("patientIds") Collection<UUID> patientIds);
}
