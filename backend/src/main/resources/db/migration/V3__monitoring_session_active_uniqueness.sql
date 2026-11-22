-- Approved by the backend lead on 2026-11-21 (Stage 8, decision F7).
-- Adds ONLY indexes: no tables, columns, types, nullability or foreign keys change.
--
-- At most ONE session with status 'ACTIVE' per device assignment and per patient.
-- ENDED / CANCELLED (historical) rows are not affected. Reversible with DROP INDEX.

CREATE UNIQUE INDEX ux_monitoring_sessions_active_assignment
    ON monitoring_sessions (device_assignment_id)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX ux_monitoring_sessions_active_patient
    ON monitoring_sessions (patient_id)
    WHERE status = 'ACTIVE';
