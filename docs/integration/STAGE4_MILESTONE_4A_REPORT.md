# SOMNISENSE — Stage 4 Milestone 4A Implementation & Verification Report

**Branch:** `stage4/continuous-monitoring-simulator`  
**Specification Baseline:** [STAGE4_PRE_IMPLEMENTATION_AUDIT.md](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/STAGE4_PRE_IMPLEMENTATION_AUDIT.md) (v2.3)  
**Scope:** Milestone 4A (Backend Foundation: Flyway V6, Durable Inference Retry Infrastructure, `SimulationRun` Lifecycle & Recovery, Atomic Worker-Generation Fencing, and Session Lifecycle Integration).  
**Status:** Implemented, database-verified (`189/189` tests passing on `somnisense_test_db`), and ready for Milestone 4B (`SlidingWindowScheduler`). Milestone 4B has **not** started.

---

## 1. Flyway V6 Migration Changes

Added additive migration [V6__inference_jobs_and_simulation_leases.sql](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/resources/db/migration/V6__inference_jobs_and_simulation_leases.sql). Flyway `V1`–`V5` remain strictly untouched.

1. **`inference_window_jobs` table:**
   - Columns: `id` (`uuid PK`), `session_id` (`uuid FK -> monitoring_sessions`), `model_version` (`varchar(50)`), `window_start` (`timestamptz`), `window_end` (`timestamptz`), `status` (`RETRY_WAIT | SUCCEEDED | FAILED_EXHAUSTED | CANCELLED`), `attempt_count` (`int >= 0`), `next_retry_at` (`timestamptz`), `last_error` (`text`), `created_at` (`timestamptz`), `updated_at` (`timestamptz >= created_at`).
   - Constraints & indexes: `chk_inference_jobs_window` (`window_end > window_start`), `chk_inference_jobs_status`, `chk_inference_jobs_attempts`, `chk_inference_jobs_updated`, unique index `ux_inference_jobs_session_model_window (session_id, model_version, window_start, window_end)`, and partial due-retry index `ix_inference_jobs_retry_due (status, next_retry_at) WHERE status = 'RETRY_WAIT'`.
2. **Safe Ordered `simulation_runs` Evolution:**
   - Added `worker_generation` (`bigint NOT NULL DEFAULT 0`), `lease_expires_at` (`timestamptz`), and `worker_instance_id` (`varchar(100)`).
   - Executed pre-constraint backfill updating any pre-existing `RUNNING` rows to `PAUSED` with `lease_expires_at = NULL`, `worker_instance_id = NULL`, and `updated_at = GREATEST("started_at", now())`.
   - Added `chk_simulation_runs_generation_non_negative` (`worker_generation >= 0`), `chk_simulation_runs_running_has_lease` (`status <> 'RUNNING' OR lease_expires_at IS NOT NULL`), and partial index `ix_simulation_runs_active_lease (status, lease_expires_at) WHERE status = 'RUNNING'`.

---

## 2. Durable Inference Retry Infrastructure

Implemented in `com.somnisense.backend.prediction`:

- [InferenceJobStatus.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/prediction/entity/InferenceJobStatus.java) & [InferenceWindowJob.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/prediction/entity/InferenceWindowJob.java): JPA entity matching V6 `inference_window_jobs` under `hibernate.ddl-auto=validate`.
- [InferenceWindowJobRepository.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/prediction/repository/InferenceWindowJobRepository.java): Lookup by `(sessionId, modelVersion, windowStart, windowEnd)`, cursor `findMaxWindowEnd(sessionId, modelVersion)`, due retry queue (`findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAscWindowEndAsc`), and bulk cancellation (`cancelOpenRetriesForSession`).
- [InferenceJobService.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/prediction/service/InferenceJobService.java):
  - **Bounded Retries:** `MAX_ATTEMPTS = 4` (1 initial attempt + 3 retries) with backoff schedule `+10s`, `+30s`, `+90s`. Attempts 1–3 persist `RETRY_WAIT` without writing a premature `predictions` row.
  - **Exhaustion & Non-Retryable Failures:** On attempt 4 exhaustion (`recordRetryableFailure`) or non-retryable client/config error (`recordNonRetryableFailure`), atomically transitions the job to `FAILED_EXHAUSTED` and persists a single terminal `PredictionStatus.ERROR` row via `PredictionService.recordPrediction(...)` (preserving V5 idempotency).
  - **Success Cleanup:** `recordSuccess` persists the prediction via `PredictionService.recordPrediction(...)` and marks any associated retry job `SUCCEEDED`.
  - **Session Cancel Cleanup:** `cancelOpenRetries` marks all `RETRY_WAIT` jobs for the session `CANCELLED` with `nextRetryAt = null`.

---

## 3. `SimulationRun` Lifecycle, Control API & Startup Recovery

Implemented in `com.somnisense.backend.simulation`:

- [SimulationRun.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/simulation/entity/SimulationRun.java), [SimulationStatus.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/simulation/entity/SimulationStatus.java), [SimulationRunRepository.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/simulation/repository/SimulationRunRepository.java).
- [SimulationWorkerClient.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/simulation/client/SimulationWorkerClient.java): Internal `java.net.http.HttpClient` communicating with Python `simulator-worker` using `X-Internal-Sim-Key` and `X-Correlation-Id` with explicit connect/read timeouts and zero secret logging.
- [SimulationLifecycleService.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/simulation/service/SimulationLifecycleService.java):
  - **Transaction Boundaries:** Uses `TransactionTemplate` for short PostgreSQL transactions (`SELECT ... FOR UPDATE`); all external worker HTTP calls execute strictly **outside** database transactions so row locks are never held across network I/O.
  - **Compensating Failure Safety:** If `/internal/sim/start` or `/internal/sim/resume` dispatch fails, a short compensating transaction marks the run `FAILED` with `lease_expires_at = null` (if still on that dispatched generation) so no orphaned `RUNNING` lease remains.
  - **Startup Recovery:** `@EventListener(ApplicationReadyEvent.class)` transitions any `RUNNING` `simulation_runs` rows from a prior process to `PAUSED` with `lease_expires_at = NULL` and `worker_instance_id = NULL`, preserving `worker_generation` and `last_step`.
  - **Stale Lease Expiry:** `expireStaleLeases()` transitions expired `RUNNING` runs (`lease_expires_at <= now`) back to `PAUSED`.
- [SimulationController.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/simulation/controller/SimulationController.java): Exposes `POST /api/v1/sessions/{sessionId}/simulation/{start,pause,resume,stop}` and `GET /api/v1/sessions/{sessionId}/simulation` restricted to `ADMIN` and `OPERATOR` on `SIMULATED` devices.

---

## 4. Worker-Generation Fencing & Ingestion Changes

- [IngestReadingsRequest.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/ingestion/dto/IngestReadingsRequest.java): Added optional `simulationRunId`, `workerGeneration`, and `workerInstanceId`.
- [IngestionService.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/ingestion/service/IngestionService.java):
  - Acquires `SELECT ... FOR UPDATE` on `SimulationRun` (`findBySessionIdForUpdate`) when `deviceType == SIMULATED`, then captures `receivedAt = Instant.now()` after lock acquisition.
  - Enforces `SIMULATION_FENCING_REQUIRED` (`409`), `SIMULATION_MISMATCH` (`409`), `SIMULATION_NOT_RUNNING` (`409`), `STALE_WORKER_GENERATION` (`409`), and `SIMULATION_LEASE_EXPIRED` (`409`) whenever a `SimulationRun` exists.
  - Preserves 100% backward compatibility for `PHYSICAL` devices (rejecting simulator fencing payloads with `SIMULATION_DEVICE_NOT_SIMULATED`) and `SIMULATED` sessions without a `SimulationRun` row.
  - **Contiguous `last_step` Checkpoint:** Advances `last_step` strictly contiguously from `last_step + 1` using newly inserted `{deviceCode}-{sessionCode}-{step:08d}` keys plus a bounded `sensor_readings` B-tree lookahead (`LOOKAHEAD_STEPS_SQL`) when bridging out-of-order/duplicate-overlap gaps, while renewing `lease_expires_at = receivedAt + leaseDuration`.

---

## 5. Session Lifecycle Integration

- [MonitoringSessionRepository.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/session/repository/MonitoringSessionRepository.java): Added ordered session status query methods (`findByStatusOrderByStartedAtAscIdAsc`, `findByStatusInOrderByStartedAtAscIdAsc`) to support Milestone 4B scheduling.
- [SessionService.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/session/service/SessionService.java): `end(...)` and `cancel(...)` now use `TransactionTemplate` to atomically finish the `MonitoringSession`, mark any associated `SimulationRun` `STOPPED`, and cancel open `RETRY_WAIT` jobs (on `CANCELLED`) before sending best-effort worker `/stop` HTTP notifications outside the database transaction.

---

## 6. Verification & Test Evidence

Executed against dedicated `somnisense_test_db` via the interactive QA launcher:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\qa\run_stage2_backend_tests.ps1
```

Reconciled Surefire results across all **26 test classes** under `backend/target/surefire-reports`:

| Metric | Value |
|---|---|
| Total Tests | **189** |
| Passed | **189** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **0** |
| Database | `somnisense_test_db` |
| Flyway Migrations | `V1`–`V6` validated (`ddl-auto=validate`) |

Key suite breakdown:
- `SimulationMigrationV6AndFencingTest`: `5/5` passed (`0.847 s`) — generation fencing, contiguous `last_step` gap bridging & duplicate idempotency, startup `RUNNING -> PAUSED` recovery, expired lease rejection/expiry, dispatch-failure `FAILED` compensation, session cancel cleanup, physical/unrun backward compatibility, V1–V6 history, PostgreSQL constraint enforcement, and backfill SQL verification.
- `InferenceJobServiceTest`: `3/3` passed (`0.400 s`) — `10s/30s/90s` backoff, attempt 4 exhaustion writing terminal `ERROR`, retry-to-`SUCCEEDED`, and non-retryable immediate `ERROR`.
- `PredictionMigrationAndConstraintsTest`: `3/3` passed (`0.308 s`) — Stage 2 fixture row updated from `RUNNING` to `PAUSED` to satisfy V6 `chk_simulation_runs_running_has_lease` without weakening any FK, uniqueness, or CHECK assertion.
- All Stage 1–3 regression suites (`BackendApplicationTests`, `IngestionTest`, `ConcurrentIngestionTest`, `ReadingQueryTest`, `SessionTest`, `ConcurrentSessionTest`, `WindowEvaluationServiceTest`, `PredictionServiceTest`, `PredictionApiTest`, auth, users, patients, devices, assignments): all passed with `0` failures, `0` errors, `0` skipped.

---

## 7. Known Limitations (Deferred to Subsequent Milestones)

- **Continuous Scheduling (`Milestone 4B`):** `SlidingWindowScheduler` and retryable `AiSidecarException` status classification are not yet implemented; scheduled evaluation does not yet run automatically in the background.
- **Standalone Python Simulator (`Milestone 4C`):** `simulator-worker/` is not yet implemented; `SimulationLifecycleService` currently requires the external simulator worker HTTP service at `somnisense.simulation.worker-base-url` (`http://127.0.0.1:8095`) when starting/resuming runs in live mode.
- **Dashboard Summary & UI (`Milestone 4D`):** `GET /api/v1/dashboard/summary` and React live-monitoring wiring remain for Milestone 4D.
