-- Approved by the backend lead on 2026-11-21 (Stage 7, decision F7).
-- Adds ONLY indexes: no tables, columns, types, nullability or foreign keys change.
--
-- A device_assignments row is "active" while released_at IS NULL.
-- These partial unique indexes allow at most ONE active row per device and ONE per patient.
-- Released (historical) rows are not affected. Reversible with DROP INDEX.

CREATE UNIQUE INDEX ux_device_assignments_active_device
    ON device_assignments (device_id)
    WHERE released_at IS NULL;

CREATE UNIQUE INDEX ux_device_assignments_active_patient
    ON device_assignments (patient_id)
    WHERE released_at IS NULL;
