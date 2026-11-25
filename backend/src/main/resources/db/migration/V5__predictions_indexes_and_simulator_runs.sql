-- Stage 2: prediction lookup/uniqueness indexes and simulator-run metadata table.
-- Additive migration only: V1-V4 remain strictly untouched.

-- 1. Guard against pre-existing duplicate window rows before creating the unique index.
--    Never silently deletes or rewrites existing prediction records.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM predictions
        GROUP BY session_id, model_version, window_start, window_end
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot create ux_predictions_session_model_window: duplicate (session_id, model_version, window_start, window_end) rows exist in predictions';
    END IF;
END $$;

-- Duplicate protection: at most one prediction per (session, model_version, window_start, window_end).
CREATE UNIQUE INDEX ux_predictions_session_model_window
    ON predictions (session_id, model_version, window_start, window_end);

-- Fast newest-first prediction history and latest-prediction lookup per session.
CREATE INDEX ix_predictions_session_window_end
    ON predictions (session_id, window_end DESC);

-- 2. Simulator run metadata (configuration and progress only; never stores ground-truth annotations).
CREATE TABLE "simulation_runs" (
  "id" uuid PRIMARY KEY,
  "session_id" uuid UNIQUE NOT NULL,
  "scenario_id" varchar(60) NOT NULL,
  "seed" bigint NOT NULL,
  "cadence_hz" numeric(6,2) NOT NULL,
  "playback_speed" numeric(6,2) NOT NULL,
  "quality_preset" varchar(60),
  "status" varchar(20) NOT NULL,
  "last_step" bigint NOT NULL DEFAULT 0,
  "started_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL,
  CONSTRAINT chk_simulation_runs_scenario_not_blank CHECK (btrim("scenario_id") <> ''),
  CONSTRAINT chk_simulation_runs_cadence_positive CHECK ("cadence_hz" > 0),
  CONSTRAINT chk_simulation_runs_playback_positive CHECK ("playback_speed" > 0),
  CONSTRAINT chk_simulation_runs_status_valid CHECK ("status" IN ('PENDING', 'RUNNING', 'PAUSED', 'COMPLETED', 'STOPPED', 'FAILED')),
  CONSTRAINT chk_simulation_runs_last_step_non_negative CHECK ("last_step" >= 0),
  CONSTRAINT chk_simulation_runs_updated_after_started CHECK ("updated_at" >= "started_at")
);

ALTER TABLE "simulation_runs"
  ADD CONSTRAINT fk_simulation_runs_session
  FOREIGN KEY ("session_id") REFERENCES "monitoring_sessions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

CREATE INDEX ix_simulation_runs_status
  ON "simulation_runs" ("status");
