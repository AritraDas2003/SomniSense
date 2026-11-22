-- Approved by the backend lead on 2026-11-21 (Stage 9).
-- Adds ONLY indexes: no tables, columns, types, nullability or foreign keys change.

-- Duplicate protection: a device can never store the same reading_key twice, so a retried batch is safe.
-- Rows without a reading_key (none are created by the API, which requires it) are not limited.
CREATE UNIQUE INDEX ux_sensor_readings_device_reading_key
    ON sensor_readings (device_id, reading_key)
    WHERE reading_key IS NOT NULL;

-- Fast "readings of this session in time order / in a time window" and "latest reading" queries.
CREATE INDEX ix_sensor_readings_session_measured
    ON sensor_readings (session_id, measured_at);
