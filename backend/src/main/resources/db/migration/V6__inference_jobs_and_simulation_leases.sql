-- Stage 4: Durable inference retry jobs and simulator worker lease/fencing columns.
-- Additive migration only: V1-V5 remain strictly untouched.

-- 1. Durable sliding-window retry tracking
CREATE TABLE "inference_window_jobs" (
  "id" uuid PRIMARY KEY,
  "session_id" uuid NOT NULL,
  "model_version" varchar(50) NOT NULL,
  "window_start" timestamptz NOT NULL,
  "window_end" timestamptz NOT NULL,
  "status" varchar(20) NOT NULL,
  "attempt_count" int NOT NULL DEFAULT 0,
  "next_retry_at" timestamptz,
  "last_error" text,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL,
  CONSTRAINT chk_inference_jobs_window CHECK ("window_end" > "window_start"),
  CONSTRAINT chk_inference_jobs_status CHECK ("status" IN ('RETRY_WAIT', 'SUCCEEDED', 'FAILED_EXHAUSTED', 'CANCELLED')),
  CONSTRAINT chk_inference_jobs_attempts CHECK ("attempt_count" >= 0),
  CONSTRAINT chk_inference_jobs_updated CHECK ("updated_at" >= "created_at")
);

ALTER TABLE "inference_window_jobs"
  ADD CONSTRAINT fk_inference_jobs_session
  FOREIGN KEY ("session_id") REFERENCES "monitoring_sessions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

CREATE UNIQUE INDEX ux_inference_jobs_session_model_window
  ON "inference_window_jobs" ("session_id", "model_version", "window_start", "window_end");

CREATE INDEX ix_inference_jobs_retry_due
  ON "inference_window_jobs" ("status", "next_retry_at")
  WHERE "status" = 'RETRY_WAIT';

-- 2. Durable simulator worker fencing & lease columns on V5 simulation_runs (safe ordered migration)
ALTER TABLE "simulation_runs"
  ADD COLUMN "worker_generation" bigint NOT NULL DEFAULT 0,
  ADD COLUMN "lease_expires_at" timestamptz,
  ADD COLUMN "worker_instance_id" varchar(100);

-- Backfill any pre-existing RUNNING rows to PAUSED before adding the lease CHECK constraint
UPDATE "simulation_runs"
SET "status" = 'PAUSED',
    "lease_expires_at" = NULL,
    "worker_instance_id" = NULL,
    "updated_at" = GREATEST("started_at", now())
WHERE "status" = 'RUNNING';

ALTER TABLE "simulation_runs"
  ADD CONSTRAINT chk_simulation_runs_generation_non_negative CHECK ("worker_generation" >= 0),
  ADD CONSTRAINT chk_simulation_runs_running_has_lease CHECK ("status" <> 'RUNNING' OR "lease_expires_at" IS NOT NULL);

CREATE INDEX ix_simulation_runs_active_lease
  ON "simulation_runs" ("status", "lease_expires_at")
  WHERE "status" = 'RUNNING';
