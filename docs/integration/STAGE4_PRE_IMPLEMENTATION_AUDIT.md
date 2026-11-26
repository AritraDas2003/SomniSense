# SOMNISENSE — STAGE 4 PRE-IMPLEMENTATION AUDIT (v2.3)
## Automated Sliding-Window Scheduling, Durable Retry, Fenced Simulator S1–S3 Worker & Full-Stack Validation

> **Audit Date:** 2026-11-26  
> **Revision:** v2.3 (Final Implementation Safeguards — V6 Backfill, Atomic Fencing & Stage 3 Retry Reconciliation)  
> **Baseline Branch:** `stage3/fastapi-model-c-integration` @ `113b131e072ebb3afb504e952e4ce1b4e50c7add`  
> **Authoritative Workspace:** `C:\Users\LORD\Downloads\Minor_Project\SomniSense_Team`  
> **Preserved Prototype Workspace (Read-Only):** `C:\Users\LORD\Downloads\Minor_Project\SomniSense`  
> **Stage 4 Implementation Status:** **NOT STARTED (Awaiting Final Authorization)**

---

## 1. Final Architecture & Component Responsibilities

```
┌─────────────────────────────────────────────────────────────────────────┐
│ React 19 + Vite Frontend (Browser)                                      │
│  - Communicates EXCLUSIVELY with Spring Boot (/api/v1/*)                │
│  - Polls readings/latest, predictions/latest, dashboard/summary         │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │ Cookie Session + CSRF
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Spring Boot 4.2 Backend (Sole Public API & PostgreSQL Owner)            │
│  - Session lifecycle (ACTIVE / ENDED / CANCELLED)                       │
│  - Reading ingestion (POST /api/v1/ingestion/readings, X-Ingestion-Key) │
│  - SimulationRun lifecycle coordinator (V5 simulation_runs)             │
│  - Sliding-window scheduler & retry state machine (V6)                  │
│  - WindowEvaluationService -> PredictionService (V5 predictions)        │
└──────────┬─────────────────────────────▲────────────────────┬───────────┘
           │ POST /internal/ai/          │ POST /api/v1/      │ POST /internal/sim/*
           │      evaluate-window        │  ingestion/readings│ (X-Internal-Sim-Key)
           │ (X-Internal-AI-Key)         │ (X-Ingestion-Key)  │
           ▼                             │                    ▼
┌────────────────────────────┐   ┌───────┴────────────────────────────────┐
│ Python FastAPI AI Sidecar  │   │ Standalone Python Simulator Worker     │
│ (ai-service/, inference    │   │ (simulator-worker/, S1-S3 engine)      │
│  only, NO DB connection)   │   │ - Signal generation only, NO DB access │
└────────────────────────────┘   └────────────────────────────────────────┘
```

| Component | Strict Responsibility | Prohibited |
|---|---|---|
| **Spring Boot** | Sole public API, PostgreSQL owner, session & `SimulationRun` lifecycle, `@Scheduled` window orchestrator, retry state machine, `PredictionService` | Feature extraction, ML inference, direct simulator signal math |
| **Python `ai-service`** | Stateless `POST /internal/ai/evaluate-window` (360s causal grid, 16 features, `VALID`-only `ModelCInference`) | PostgreSQL access, scheduling, simulator execution |
| **Python `simulator-worker`** | Deterministic S1–S3 physiological signal & S3 degradation generation, batching to `POST /api/v1/ingestion/readings` | PostgreSQL access, DB credentials, inference, storing ground-truth labels |
| **React Frontend** | Staff UI, bounded polling while `ACTIVE`, non-clinical safety banners | Calling Python services directly |

---

## 2. Scheduler Cursor Guarantees & Multi-Instance Readiness

### 2.1 Cursor Formula & Scoping
For each session `sessionId` and configured `modelVersion` (`phase7_model_c:1.0.0`):

```text
nextWindowEnd(sessionId, modelVersion) =
    max(
        MAX(predictions.window_end WHERE session_id = :sessionId AND model_version = :modelVersion),
        MAX(inference_window_jobs.window_end WHERE session_id = :sessionId AND model_version = :modelVersion)
    ) + 10s
```
If neither table has a row for `(sessionId, modelVersion)`, `nextWindowEnd = session.startedAt + 60s`.

### 2.2 Explicit Cursor Invariants
1. **Strict Chronological Processing:** Within each session, new windows are evaluated strictly in ascending `windowEnd` order (`nextWindowEnd`, `nextWindowEnd + 10s`, ...).
2. **Durable Persistence Before Cursor Advancement:** `nextWindowEnd` is derived directly from committed PostgreSQL rows (`predictions` and `inference_window_jobs`). If the process crashes during an HTTP call to `ai-service` *before* committing either row, `nextWindowEnd` does **not** advance, so the window is automatically re-evaluated on restart.
3. **Independent Retry Processing:** Transiently failed windows sit in `inference_window_jobs` (`status = 'RETRY_WAIT'`) and are retried independently by the retry pass when `next_retry_at <= now()`, without blocking newer windows from advancing `nextWindowEnd`.
4. **Model-Version & Session Scoping:** Both `predictions` and `inference_window_jobs` queries filter strictly by `(session_id, model_version)`, matching `ux_predictions_session_model_window` and `ux_inference_jobs_session_model_window`.
5. **Single-Instance vs. Multi-Instance Locking:**
   - **Single-Instance Deployment (Current):** An in-JVM per-session `ReentrantLock` (`ConcurrentHashMap<UUID, ReentrantLock>`) is **sufficient** to prevent two overlapping `@Scheduled` ticks from evaluating the same session concurrently, backed by database unique constraints (`ux_predictions_session_model_window`, `ux_inference_jobs_session_model_window`).
   - **Future Multi-Instance Deployment (No Long-Running DB Transactions):** Transaction-scoped advisory locks (`pg_try_advisory_xact_lock`) must **never** be used across an AI HTTP call (`2s`–`5s`), as holding a PostgreSQL transaction open during external I/O exhausts the HikariCP connection pool. Instead, future multi-instance deployments must use **short-lived, non-transactional lease rows** (e.g. a `scheduler_leases` table with `owner_instance_id` and `lease_expires_at` acquired/renewed in millisecond-fast commits before/after the HTTP call, or ShedLock), releasing DB connections before invoking `ai-service`.

---

## 3. Retry Policy, Additive V6 Schema & Atomic Transaction Boundaries

### 3.1 Additive Flyway V6 Migration (`V6__inference_jobs_and_simulation_leases.sql`)
Flyway `V1`–`V5` remain **strictly unmodified**. `V6__inference_jobs_and_simulation_leases.sql` adds `inference_window_jobs` and extends `simulation_runs` with durable worker lease/fencing columns:

```sql
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
    "updated_at" = now()
WHERE "status" = 'RUNNING';

ALTER TABLE "simulation_runs"
  ADD CONSTRAINT chk_simulation_runs_generation_non_negative CHECK ("worker_generation" >= 0),
  ADD CONSTRAINT chk_simulation_runs_running_has_lease CHECK ("status" <> 'RUNNING' OR "lease_expires_at" IS NOT NULL);

CREATE INDEX ix_simulation_runs_active_lease
  ON "simulation_runs" ("status", "lease_expires_at")
  WHERE "status" = 'RUNNING';
```

### 3.2 Retry Count, Backoff & Error Classification
- **Total Attempts:** **1 initial attempt + 3 retries = maximum 4 total attempts**.
- **Backoff Schedule:** After attempt 1 fails → `+10s`; after attempt 2 fails → `+30s`; after attempt 3 fails → `+90s`; after attempt 4 fails → **exhausted** (`FAILED_EXHAUSTED`).
- **Retryable Errors (transition to `RETRY_WAIT` until 4 attempts exhausted):**
  - Network/connection errors (`IOException`, connection refused/reset), HTTP client read/connect timeouts.
  - HTTP `429 Too Many Requests` and HTTP `5xx` (`500`, `502`, `503`, `504`) from `ai-service`.
- **Non-Retryable Errors (fail immediately without retry):**
  - Authentication/authorization errors (`401`, `403`, missing `AI_SERVICE_API_KEY`).
  - Client/request validation errors (`400`, `422`, invalid window bounds).
  - *Action:* Persist `PredictionStatus.ERROR` immediately (and mark job `FAILED_EXHAUSTED` if a job exists) so misconfigurations surface immediately to operators.
- **Permanent Quality Rejections (`REJECTED` / `DEGRADED`):**
  - Never retried; persisted directly to `predictions` with `PredictionStatus.REJECTED`, `InputQualityStatus.DEGRADED` or `REJECTED`, `predictedClass = null`, `confidenceScore = null`.

*(Implementation note: `AiSidecarException` in Stage 3 will be extended with `int httpStatus` / `boolean retryable` so the scheduler distinguishes `429`/`5xx`/transport failures from `400`/`401`/`403`/`422`.)*

### 3.3 Stage 3 Orchestration Refactor, Atomic Transaction Boundaries & Crash Recovery
Stage 3 `WindowEvaluationService.evaluateAndPersist(...)` currently catches `AiSidecarException` and immediately persists `PredictionStatus.ERROR`. To integrate durable retries without breaking Stage 3 callers:
- Keep `evaluateAndPersist(...)` intact for direct Stage 3 callers/tests, and expose a scheduler-facing evaluation path where the `ai-service` HTTP call executes **outside** any database transaction:
  1. **Sidecar Call (No Open DB Transaction):** Read `findWindowOrdered`, invoke `aiSidecarClient.evaluateWindow(...)`, and return the raw outcome or `AiSidecarException(httpStatus, retryable)`.
  2. **On 2xx (`COMPLETED`, `NEEDS_REVIEW`, `REJECTED`):** Inside one `@Transactional` boundary, persist via `PredictionService.recordPrediction(...)` and upsert `inference_window_jobs.status = 'SUCCEEDED'` (`next_retry_at = null`, `updated_at = now`).
  3. **On Retryable `AiSidecarException` (`IOException`/timeout/`429`/`5xx`):** Inside one `@Transactional` boundary, upsert `inference_window_jobs` (`attempt_count = attempt_count + 1`). If `attempt_count < 4`, set `status = 'RETRY_WAIT'` and `next_retry_at = now + backoff(attempt_count)` (`10s`/`30s`/`90s`) **without** writing `PredictionStatus.ERROR` to `predictions`. If `attempt_count >= 4`, atomically persist terminal `PredictionStatus.ERROR` (`predictedClass = null`, `confidenceScore = null`, safe `last_error`) via `PredictionService.recordPrediction(...)` and set `status = 'FAILED_EXHAUSTED'`.
  4. **On Non-Retryable `AiSidecarException` (`400`/`401`/`403`/`422`):** Atomically persist terminal `PredictionStatus.ERROR` via `PredictionService.recordPrediction(...)` and set `inference_window_jobs.status = 'FAILED_EXHAUSTED'`.
- **Crash After Inference Succeeds Before DB Commit:**
  - Because `ai-service` is stateless, no duplicate sidecar state exists.
  - On restart, the window is still eligible (either via `nextWindowEnd` if attempt 1, or `RETRY_WAIT` if a retry), re-evaluated deterministically, and persisted once. If a concurrent write somehow committed right before crash, `ux_predictions_session_model_window` guarantees idempotent handling without duplicate rows.

---

## 4. Session Lifecycle & `ENDED` Session Compatibility

### 4.1 Verified Stage 3 `WindowEvaluationService` Behaviour
Inspection of `backend/src/main/java/com/somnisense/backend/ai/service/WindowEvaluationService.java` (lines 67–70) confirms:
```java
if (session.getStatus() == SessionStatus.CANCELLED) {
    throw new ConflictException("Cannot evaluate windows for a CANCELLED monitoring session");
}
```
`WindowEvaluationService` **already permits** evaluating completed 60s windows for `ENDED` sessions and **already rejects** `CANCELLED` sessions. No breaking change to Stage 3 is required.

### 4.2 Exact Scheduler Rules by Session State

| State | New Window Eligibility | Retry (`RETRY_WAIT`) Handling | Incomplete Trailing Windows |
|---|---|---|---|
| `ACTIVE` | `wEnd <= now - ingestionGraceLag` (`3s`) | Retry due `RETRY_WAIT` jobs (`next_retry_at <= now`) | Not yet complete; wait for `wEnd + 3s` |
| `ENDED` | `wEnd <= session.endedAt` AND `now >= wEnd + ingestionGraceLag` | Continue retrying due `RETRY_WAIT` jobs (`wEnd <= session.endedAt`) until `SUCCEEDED` or `FAILED_EXHAUSTED` | **Never evaluated** (`wEnd > session.endedAt` skipped permanently) |
| `CANCELLED` | Stopped immediately; 0 new windows | Transition open `RETRY_WAIT` jobs for `sessionId` to `CANCELLED` | **Never evaluated** |

---

## 5. Finalized Simulator Worker Operations, Fencing & Ingestion Validation (`simulator-worker/`)

### 5.1 Durable Lease & Fencing Token Mechanism
1. **Control Protocol (RESOLVED):** **Spring Boot → `simulator-worker` internal HTTP** using shared header `X-Internal-Sim-Key` (symmetric with `X-Internal-AI-Key` for `ai-service`).
   - Internal worker endpoints: `POST /internal/sim/start`, `POST /internal/sim/pause`, `POST /internal/sim/resume`, `POST /internal/sim/stop`, `GET /internal/sim/status`, `GET /internal/sim/health`.
   - Spring Boot staff endpoints (`ADMIN` / `OPERATOR`, `SIMULATED` devices only): `POST /api/v1/sessions/{sessionId}/simulation/{start,pause,resume,stop}` and `GET /api/v1/sessions/{sessionId}/simulation`.
2. **Monotonic `worker_generation` & `lease_expires_at`:**
   - **Start:** Sets `worker_generation = 1`, `status = 'RUNNING'`, `lease_expires_at = now + leaseDuration` (default `30s`), `worker_instance_id = <workerId>`. Spring Boot passes `(simulationRunId, workerGeneration, lastStep)` to `simulator-worker`.
   - **Pause / Stop / Restart (`RUNNING → PAUSED`):** Sets `status = 'PAUSED'` (or `'STOPPED'`) and clears `lease_expires_at = NULL`, preserving `worker_generation`.
   - **Resume:** Increments `worker_generation = worker_generation + 1`, sets `status = 'RUNNING'`, `lease_expires_at = now + leaseDuration`, `worker_instance_id = <workerId>`, and dispatches the **new** `workerGeneration` and `lastStep + 1` to `simulator-worker`.
   - **Lease Renewal:** Heartbeats or valid ingestion batches from the active generation renew `lease_expires_at = now + leaseDuration` and advance `last_step`.

### 5.2 Ingestion Validation & Physical-Device Backward Compatibility
Inspection of `IngestReadingsRequest.java` and `IngestionService.java` confirms `POST /api/v1/ingestion/readings` routes by `deviceCode` → active `DeviceAssignment` → `ACTIVE` `MonitoringSession`. Checking `MonitoringSession.status` alone is **insufficient** because an orphan worker can keep hitting an `ACTIVE` session after `SimulationRun` was marked `PAUSED` on backend restart.

- **Smallest Safe Contract Addition:** Add optional fields `simulationRunId` (`UUID`, nullable) and `workerGeneration` (`Long`, nullable) to `IngestReadingsRequest` (or matching optional headers `X-Simulation-Run-Id` / `X-Worker-Generation`):
  1. **`PHYSICAL` devices:** Ignore/forbid simulator lease fields; behaviour is 100% unchanged from Stage 9.
  2. **`SIMULATED` devices without a `simulation_runs` row** (manual `curl`, Postman, Stage 9 `IngestionTest`): Allow backward-compatible ingestion when `simulationRunId` and `workerGeneration` are both absent (reject `409` if `simulationRunId` is sent when no run exists).
  3. **`SIMULATED` devices with an existing `simulation_runs` row** (managed simulator sessions, atomic `SELECT ... FOR UPDATE`):
     - Inside `IngestionService.ingest(...)` (`@Transactional`), acquire `SELECT ... FOR UPDATE` on the `SimulationRun` row (`findBySessionIdForUpdate(session.getId())`) using authoritative server UTC time (`receivedAt = Instant.now()`, never client-supplied time).
     - Both `simulationRunId` and `workerGeneration` are **required** (`409 SIMULATION_FENCING_REQUIRED` if missing).
     - `simulationRunId` must equal `run.getId()` (`409 SIMULATION_MISMATCH` otherwise).
     - `run.getStatus()` must be `RUNNING` (`409 SIMULATION_NOT_RUNNING` for `PAUSED`, `STOPPED`, `COMPLETED`, `FAILED`).
     - `workerGeneration` must equal `run.getWorkerGeneration()` (`409 STALE_WORKER_GENERATION` for any older generation).
     - `run.getLeaseExpiresAt()` must be `> receivedAt` (`409 SIMULATION_LEASE_EXPIRED` if expired).
     - Within that **same** transaction: persist `sensor_readings` (`ON CONFLICT DO NOTHING`), advance `run.last_step` / `run.updated_at = receivedAt`, and renew `run.lease_expires_at = receivedAt + leaseDuration`.
     - Lifecycle methods (`pause`, `stop`, `resume`) also acquire `SELECT ... FOR UPDATE` on the same `SimulationRun` row in a short DB transaction *before* calling `simulator-worker` over HTTP (never holding DB transactions open across external HTTP calls), ensuring an old generation can never ingest after a newer generation or `PAUSED`/`STOPPED` state has committed.

### 5.3 Failure & Restart Scenarios Matrix

| Scenario | Expected Backend & Worker Behaviour |
|---|---|
| **Spring Boot restarts while worker stays alive** | Startup transitions `RUNNING` → `PAUSED`, `lease_expires_at = NULL`. Orphan worker's next batch gets `409 SIMULATION_NOT_RUNNING` and self-terminates; zero readings stored while `PAUSED`. |
| **Worker restarts while Spring Boot stays alive** | Lease expires after `30s` (`lease_expires_at <= now`); backend transitions stale `RUNNING` → `PAUSED` (or `FAILED`). Operator resumes explicitly (`worker_generation + 1`) from `last_step + 1`. |
| **Network partition between worker and backend** | Worker batches fail/timeout; after `30s` lease expires, backend marks run `PAUSED`. If partition heals later, stale worker gets `409` (`SIMULATION_NOT_RUNNING` or `SIMULATION_LEASE_EXPIRED`) and stops. |
| **Operator pause / resume** | Pause sets `PAUSED` + `lease_expires_at = NULL` and sends `/internal/sim/stop`. Resume issues `worker_generation + 1` and starts from `last_step + 1`. |
| **Session end / cancel** | Ending/cancelling `MonitoringSession` stops `SimulationRun` (`STOPPED`, `lease_expires_at = NULL`); subsequent worker batches get `409 NO_ACTIVE_SESSION`. |
| **Two workers attempt same `SimulationRun`** | Only the worker holding the latest `worker_generation` passes `IngestionService` fencing; the older worker immediately gets `409 STALE_WORKER_GENERATION` and terminates. |

### 5.4 Zero-DB & Determinism Invariants
- `simulator-worker/` has **zero** PostgreSQL drivers or credentials.
- Submits readings exclusively via `POST /api/v1/ingestion/readings` with `X-Ingestion-Key`, preserving production `MEASURED_AT_IN_FUTURE` (`10s`) validation and deterministic `readingKey = "{deviceCode}-{sessionCode}-{step:08d}"` (`ON CONFLICT DO NOTHING`).

---

## 6. Accelerated QA Mechanism & Timestamp Integrity

### 6.1 Verified `StartSessionRequest` Behaviour
Inspection of `backend/src/main/java/com/somnisense/backend/session/dto/StartSessionRequest.java` (lines 12–20) confirms:
- `StartSessionRequest` accepts **only** `patientId` and optional `deviceAssignmentId`. Clients **cannot** supply `startedAt` or `startedBy` in production.
- `IngestionService` enforces `measuredAt <= receivedAt + 10s` (`MEASURED_AT_IN_FUTURE`).

### 6.2 Safe Test-Only Accelerated Mechanism
- **Production & Live E2E (`1.0x`):** Production `POST /api/v1/sessions` and `IngestionService` `MEASURED_AT_IN_FUTURE` (`10s`) validation remain **100% unmodified**. Full-stack Playwright E2E runs at `1.0x` real-time (e.g., `75s`–`90s` live session emitting 1 Hz samples produces real completed `60s` windows without backdating).
- **Accelerated JUnit / Integration Tests (`@ActiveProfiles("test")` on `somnisense_test_db`):**
  - Tests construct backdated `MonitoringSession` rows directly via `MonitoringSessionRepository` with `startedAt = Instant.now().minusSeconds(N)` (matching the existing pattern in `IngestionTestBase.java:68-70`), then ingest deterministic historical `1 Hz` readings (`measuredAt <= Instant.now()`) and run the scheduler/retry engine at full speed without touching production endpoints or weakening `MEASURED_AT_IN_FUTURE`.

---

## 7. Model Baseline Warmup & Four-Layer Test Plan

### 7.1 Early-Session Baseline Warmup Tests (`< 300s` Context)
Add explicit tests in `ai-service/tests` and `WindowEvaluationServiceTest` covering early-session windows (`[startedAt, startedAt + 60s)` where `< 240` prior baseline samples exist):
- Missing prior context remains `np.nan` (zero-filling and interpolation are strictly prohibited).
- `spo2_rolling_baseline` and `spo2_max_desaturation_depth` evaluate to `NaN` in `extract_16_features` and are imputed deterministically by Model C's frozen `SimpleImputer(strategy="median")`, matching `feature_schema.json` (`nullable: true` for those two predictors only).
- `APPROVED_16_PREDICTORS` ordering, `model_c_pipeline.joblib` SHA-256 (`faef00b0...`), and the `0.50` threshold remain untouched.

### 7.2 Four-Layer Test Separation
1. **Frozen-Model Deterministic Fixture Tests (`ai-service/tests`):** Golden 16-feature vectors, early-session warmup (`NaN` baseline imputation), `1e-9` probability parity, `0.50` threshold, `VALID`-only `predict_proba` gating.
2. **Simulator Signal-Generation Tests (`simulator-worker/tests`):** S1–S3 seeded determinism, scenario waveforms, degradation presets, `readingKey` determinism. **Never asserts specific Model C predictions from scenario labels alone.**
3. **Backend Ingestion, Retry & Scheduler Integration Tests (`somnisense_test_db`):**
   - `ACTIVE` `60s`/`10s` window progression & multi-patient round-robin fairness.
   - `RETRY_WAIT` (`10s`/`30s`/`90s`, max 4 attempts) → `SUCCEEDED` vs. `FAILED_EXHAUSTED` → `PredictionStatus.ERROR`.
   - Non-retryable `401`/`400` and permanent `REJECTED` (`DEGRADED`/`REJECTED`) behaviour.
   - `ENDED` final complete-window flush & zero incomplete trailing-window evaluation.
   - `CANCELLED` immediate termination & `RETRY_WAIT` cancellation.
   - Stale `RUNNING` → `PAUSED` startup recovery & `GET /api/v1/dashboard/summary` RBAC/efficiency.
4. **Full-Stack E2E Tests (Playwright Chromium):** Live `1.0x` multi-patient run, charts, `LatestReadings`, prediction history/latest, non-clinical safety wording (`"Model output only. This is not a diagnosis."`), `dashboard/summary`, session end, 0 console/5xx errors.

---

## 8. Architecture Approval Checklist

| Item | Decision / Verification | Status |
|---|---|---|
| **1. Scheduler Cursor & Concurrency** | `(session_id, model_version)` scoped `max(predictions, inference_window_jobs) + 10s`; durable commit before advance; `ReentrantLock` (single-instance) + non-transactional `scheduler_leases` path documented (multi-instance, no DB tx across AI HTTP calls) | **RESOLVED** |
| **2. Retry Policy & V6 Schema** | Additive `V6__inference_jobs_and_simulation_leases.sql`; 1 initial + 3 retries = max 4 attempts (`10s, 30s, 90s`); retryable (`429`/`5xx`/network/timeout) vs non-retryable (`400`/`401`/`403`/`422`/permanent `REJECTED`); atomic `@Transactional` updates | **RESOLVED** |
| **3. `ENDED` & `CANCELLED` Finalization** | Verified `WindowEvaluationService.java:67-70` allows `ENDED` and rejects `CANCELLED`; incomplete trailing windows (`wEnd > endedAt`) never evaluated | **RESOLVED** |
| **4. Simulator Fencing, Control & Recovery** | Standalone `simulator-worker/` with zero DB access; `worker_generation` fencing + `lease_expires_at` (`30s`) enforced in `IngestionService`; stale `RUNNING` → `PAUSED` on startup; stale/orphan workers rejected with `409` while preserving physical/manual ingestion compatibility | **RESOLVED** |
| **5. Accelerated QA Mechanism** | Verified `StartSessionRequest.java:12` is server-clock only; backdated `MonitoringSessionRepository` fixtures in `@ActiveProfiles("test")` JUnit + `1.0x` real-time for live E2E; `MEASURED_AT_IN_FUTURE` untouched | **RESOLVED** |
| **6. Early-Session Baseline Warmup** | Explicit tests for `< 300s` context (`np.nan` preserved, `SimpleImputer` handles nullable baseline features, zero interpolation/zero-filling, frozen `0.50` threshold) | **RESOLVED** |
| **7. Frontend & Dashboard Summary** | `GET /api/v1/dashboard/summary` for `itemsNeedingReview`; `patientService.js` wired to `/predictions` & `/latest`; `10s` polling only while `ACTIVE`; `StatusBadge` labels & safety wording preserved | **RESOLVED** |
| **8. V6 Migration Backfill** | Ordered V6 DDL: add columns → backfill `RUNNING` to `PAUSED` (`lease_expires_at = NULL`, `worker_instance_id = NULL`) → add `CHECK` constraints and indexes (`V1`–`V5` untouched) | **RESOLVED** |
| **9. Atomic Simulator Fencing** | `SELECT ... FOR UPDATE` on `SimulationRun` inside `IngestionService` and `pause`/`stop`/`resume` using server UTC time (`receivedAt`); persist readings + update `last_step`/lease atomically; no DB tx open during external HTTP | **RESOLVED** |
| **10. Stage 3 Retry Reconciliation** | Scheduler path calls `ai-service` outside DB tx; retryable errors create/update `RETRY_WAIT` (4 max attempts); non-retryable or exhausted attempts atomically write `PredictionStatus.ERROR` + `FAILED_EXHAUSTED` | **RESOLVED** |
| **11. Remaining Blockers** | None. Ready for Stage 4 implementation authorization. | **RESOLVED** |
