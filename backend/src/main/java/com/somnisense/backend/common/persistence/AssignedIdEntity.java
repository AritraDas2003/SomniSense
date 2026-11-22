package com.somnisense.backend.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.util.Objects;
import java.util.UUID;

/**
 * Base class for every entity whose UUID primary key is created in Java (our SQL has no DB default for "id").
 *
 * Why a base class?
 * - @MappedSuperclass: its fields (here: "id") become columns of each subclass's own table;
 *   this class has no table of its own.
 * - Persistable + isNew: Spring Data normally treats "id != null" as "already in the database" and runs an
 *   extra SELECT before every insert. Because we set the id ourselves, we tell Spring Data explicitly:
 *   an object is new until it has been saved (@PostPersist) or loaded from the database (@PostLoad).
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient   // not a database column
    private boolean isNew = true;

    /** Required by JPA: Hibernate uses this no-argument constructor when loading rows. */
    protected AssignedIdEntity() {
    }

    protected AssignedIdEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    /** Two entity objects are "equal" when they represent the same database row (same id). */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return id != null && id.equals(((AssignedIdEntity) other).id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
