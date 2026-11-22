package com.somnisense.backend.session.entity;

/**
 * monitoring_sessions.status - SQL comment: 'ACTIVE, ENDED or CANCELLED' (decision 2026-11-21).
 *
 * ACTIVE    - monitoring is running.
 * ENDED     - finished normally; its readings are the session's data.
 * CANCELLED - started by mistake / aborted; readings are kept but belong to a cancelled session.
 * Allowed changes: ACTIVE -> ENDED and ACTIVE -> CANCELLED only. ENDED and CANCELLED are final.
 */
public enum SessionStatus {
    ACTIVE,
    ENDED,
    CANCELLED
}
