package com.somnisense.backend.audit.entity;

import com.somnisense.backend.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the approved "audit_logs" table: who did what, to which record, and when.
 *
 * - @Immutable: Hibernate will never UPDATE an audit row; audit history is insert-only.
 * - userId is a plain UUID, not a @ManyToOne link to User. The database foreign key still guarantees it
 *   points at a real user, but writing an audit row never loads or cascades to the User entity.
 * - details is PostgreSQL "jsonb". @JdbcTypeCode(SqlTypes.JSON) tells Hibernate to send the String as JSON.
 *   NEVER put passwords, tokens or medical data in details.
 */
@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLog extends AssignedIdEntity {

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    private String detailsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public AuditLog(UUID userId, String action, String entityType, UUID entityId, String detailsJson) {
        super(UUID.randomUUID());
        this.userId = userId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.detailsJson = detailsJson;
        this.createdAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getDetailsJson() {
        return detailsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
