package com.somnisense.backend.audit.service;

import com.somnisense.backend.audit.entity.AuditLog;
import com.somnisense.backend.audit.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Writes audit_logs rows for important operations.
 *
 * - @Service: a Spring-managed business class (a "bean").
 * - @Transactional (default REQUIRED): the audit row JOINS the caller's transaction, so the change and its
 *   audit row are committed together - and if the change is rolled back, its audit row disappears too
 *   (the action never happened). When there is no surrounding transaction (e.g. a failed login), the audit
 *   row is committed on its own.
 *   (An earlier REQUIRES_NEW version failed: a separate transaction cannot see a user row that the caller
 *   has not committed yet, so the user_id foreign key rejected the audit row.)
 * - Problems building the details JSON are logged, not thrown, so they never break the user's request.
 *
 * Policy: details hold short, non-sensitive facts only (reason codes, old/new role). Never passwords,
 * tokens, raw login text or medical data.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final JsonMapper jsonMapper;

    public AuditService(AuditLogRepository auditLogRepository, JsonMapper jsonMapper) {
        this.auditLogRepository = auditLogRepository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public void record(UUID actorUserId, String action, String entityType, UUID entityId,
                       Map<String, ?> details) {
        String json = null;
        if (details != null && !details.isEmpty()) {
            try {
                json = jsonMapper.writeValueAsString(details);
            } catch (RuntimeException ex) {
                log.error("Could not serialize audit details for action {}; saving without details", action, ex);
            }
        }
        auditLogRepository.save(new AuditLog(actorUserId, action, entityType, entityId, json));
    }

    /** Shortcut when there is no target record or details. */
    public void record(UUID actorUserId, String action) {
        record(actorUserId, action, null, null, null);
    }
}
