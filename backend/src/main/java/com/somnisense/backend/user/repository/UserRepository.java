package com.somnisense.backend.user.repository;

import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Database access for users.
 *
 * Spring Data writes the SQL for us from the method NAMES:
 * - findByUsername(x)          -> select ... from users where username = ?
 * - countByRoleAndActiveTrue(r) -> select count(*) from users where role = ? and is_active = true
 * JpaRepository also gives save(), findById(), findAll(), count(), ... for free.
 * JpaSpecificationExecutor adds findAll(Specification, Pageable) for the filtered staff list (UserSpecifications).
 *
 * Callers must pass NORMALIZED values (User.normalizeUsername / normalizeEmail).
 */
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByUsername(String normalizedUsername);

    Optional<User> findByEmail(String normalizedEmail);

    boolean existsByUsername(String normalizedUsername);

    boolean existsByEmail(String normalizedEmail);

    long countByRoleAndActiveTrue(Role role);

    /**
     * Loads every ACTIVE user with the given role and LOCKS those rows (SQL "SELECT ... FOR UPDATE") until the
     * current transaction ends. Used to enforce "at least one active Admin must remain" safely when two
     * Admins act at the same time: the second transaction waits, then sees the first one's result.
     * "order by id" makes every transaction lock rows in the same order, which prevents deadlocks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role and u.active = true order by u.id")
    List<User> lockActiveByRole(@Param("role") Role role);
}
