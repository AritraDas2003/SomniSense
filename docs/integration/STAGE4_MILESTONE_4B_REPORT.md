# SOMNISENSE — Stage 4 Milestone 4B Implementation & Verification Report

**Branch:** `stage4/continuous-monitoring-simulator`  
**Specification Baseline:** [STAGE4_PRE_IMPLEMENTATION_AUDIT.md](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/STAGE4_PRE_IMPLEMENTATION_AUDIT.md) (v2.3)  
**Prior Milestone Report:** [STAGE4_MILESTONE_4A_REPORT.md](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/STAGE4_MILESTONE_4A_REPORT.md)  
**Scope:** Milestone 4B (Continuous `SlidingWindowScheduler`, Retry-Aware AI Evaluation, Failure Classification, Durable Cursor & Restart Recovery, and Bounded Multi-Session Fairness).  
**Status:** Implemented, database-verified (`197/197` Maven tests on `somnisense_test_db`, `7/7` Python `ai-service` tests), ready for acceptance. Milestones 4C and 4D have **not** started.

---

## 1. Scheduler Architecture & Configuration

Implemented externalized configuration and conditional scheduling without touching Flyway `V1`–`V6`, frozen Model C artifacts, `frontend/`, or `ai-service/`:

- **[AiSchedulerProperties.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/ai/config/AiSchedulerProperties.java) & [application.properties](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/resources/application.properties):**
  - `somnisense.ai.scheduler.enabled` (`true` default; `false` in `application-test.properties` for deterministic tests)
  - `somnisense.ai.scheduler.fixed-delay-ms` (`2000`)
  - `somnisense.ai.scheduler.grace-lag` (`3s`)
  - `somnisense.ai.scheduler.window-duration` (`60s`)
  - `somnisense.ai.scheduler.window-stride` (`10s`)
  - `somnisense.ai.scheduler.causal-context-duration` (`360s`)
  - `somnisense.ai.scheduler.max-windows-per-session` (`6`)
  - `somnisense.ai.scheduler.worker-threads` (`4`)
  - `somnisense.ai.scheduler.max-retry-attempts` (`4`)
  - `somnisense.ai.scheduler.retry-backoff-seconds` (`10,30,90`)
- **[AiSchedulingConfiguration.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/ai/config/AiSchedulingConfiguration.java):** Enables `@EnableScheduling` only when `somnisense.ai.scheduler.enabled=true` while leaving the [SlidingWindowScheduler.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/ai/service/SlidingWindowScheduler.java) bean available for deterministic `tickAt(Instant)` tests.

---

## 2. Window Timing, Causal Context & Session Lifecycle Rules

- **Window Geometry:** `window_0 = [startedAt, startedAt + 60s)`, `window_k = [startedAt + 10k, startedAt + 60s + 10k)`, each requesting `360s` ordered causal context `[windowEnd - 360s, windowEnd)` for `phase7_model_c:1.0.0` (decision threshold `0.50` remains unchanged inside `ai-service`).
- **Session Lifecycle Semantics:**
  - **`ACTIVE`:** Evaluates completed windows strictly satisfying `windowEnd <= now - graceLag` (`3s`), advancing chronologically.
  - **`ENDED`:** Uses cutoff `min(now - graceLag, endedAt)` so ingestion grace lag is respected immediately after end, complete windows up to `windowEnd <= endedAt` finish cleanly (along with eligible due `RETRY_WAIT` jobs), and incomplete trailing windows (`windowEnd > endedAt`, or sessions lasting `< 60s`) are never evaluated. [MonitoringSessionRepository.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/session/repository/MonitoringSessionRepository.java) (`findCandidateSessionsForScheduling`) automatically stops scanning `ENDED` sessions once their last complete window has been scheduled/persisted.
  - **`CANCELLED`:** Stops scheduling new windows immediately, cancels open `RETRY_WAIT` jobs (`cancelOpenRetriesForSession`), and persists zero new predictions.

---

## 3. Durable Cursor, Restart Recovery & Non-Skipping Guard

- **Cursor Formula ([PredictionRepository.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/prediction/repository/PredictionRepository.java) & [InferenceWindowJobRepository.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/prediction/repository/InferenceWindowJobRepository.java)):**
  - `nextWindowEnd = max(MAX(predictions.window_end), MAX(inference_window_jobs.window_end)) + 10s` (scoped by `sessionId` and `modelVersion`), or `startedAt + 60s` if no prior outcome exists.
  - Due `RETRY_WAIT` jobs (`next_retry_at <= now`) are queried via `ix_inference_jobs_retry_due` and processed independently of the forward cursor.
- **Non-Skipping Invariant:** Before dispatching a forward window inside its per-session lock, `SlidingWindowScheduler` verifies `haltedSessionsThisTick` and recalculates `expectedNextEnd` from committed database records. If an earlier chronological window crashed mid-HTTP before persisting a `Prediction` or `InferenceWindowJob`, later rounds in the same tick immediately halt for that session rather than advancing `MAX(window_end)` over the uncommitted window. Restarting Spring Boot resumes directly at the uncommitted window.

---

## 4. Retry Classification, Schedule & Atomic Persistence

- **Error Classification ([AiSidecarException.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/ai/client/AiSidecarException.java) & [AiSidecarClient.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/ai/client/AiSidecarClient.java)):**
  - **Retryable (`isRetryable() == true`):** Connection failures (`IOException`), connect/read timeouts (`HttpTimeoutException`), thread interruption, HTTP `408`, `429`, and `5xx` (`500`, `502`, `503`, ...).
  - **Non-Retryable (`isRetryable() == false`):** HTTP `400`, `401`, `403`, `422` (and other client 4xx except `408`/`429`), invalid/short API key configuration, serialization failures, and malformed/incomplete JSON responses.
- **Retry Schedule & Atomic Transactions ([InferenceJobService.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/prediction/service/InferenceJobService.java)):**
  - Attempts 1–3 on retryable failures record `RETRY_WAIT` with backoff `+10s`, `+30s`, `+90s` (`next_retry_at`) and write **zero** premature `PredictionStatus.ERROR` rows.
  - Attempt 4 exhaustion transitions the job to `FAILED_EXHAUSTED` and writes one terminal `PredictionStatus.ERROR` (`predictedClass = null`, `confidenceScore = null`) atomically inside `TransactionTemplate`.
  - Non-retryable failures transition immediately to `FAILED_EXHAUSTED` + single terminal `PredictionStatus.ERROR` without entering `RETRY_WAIT`.
  - Permanent quality rejections (`PredictionStatus.REJECTED` with preserved `DEGRADED` or `REJECTED` `inputQualityStatus`) persist directly via `recordSuccess` and never schedule retries.

---

## 5. Concurrency, Fairness & Stage 3 Compatibility

- **Transaction & Connection Discipline ([WindowEvaluationService.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/ai/service/WindowEvaluationService.java)):** Scheduler path `evaluateOnce(...)` reads `MonitoringSession` and ordered `SensorReading` context through short repository transactions (`spring.jpa.open-in-view=false`), returning pooled DB connections **before** executing `aiSidecarClient.evaluateWindow(...)`.
- **Fairness & Locking (`SlidingWindowScheduler`):**
  - Uses an `AtomicBoolean tickRunning` guard so overlapping ticks skip immediately without queuing duplicate work.
  - Rotates candidate sessions round-robin across ticks (`roundRobinCursor`), caps catch-up at `6` new windows per session per tick, and executes round-by-round across a bounded `ThreadPoolExecutor` (`ArrayBlockingQueue`, `CallerRunsPolicy`).
  - Per-session `ReentrantLock` ensures a single session's windows execute chronologically one at a time while distinct sessions run concurrently.
- **Stage 3 Preservation:** Existing `WindowEvaluationService.evaluateAndPersist(...)` behavior (including immediate future-dated window rejection and single-shot `ERROR` persistence for direct callers) and Stage 2 `PredictionService` list/latest APIs remain unchanged.

---

## 6. Intermediate Failures Resolved Before Final Acceptance

The intermediate database-backed QA run failed on 4 cases (`192/196` passing). Every root cause was fixed without weakening any test assertion:

1. **`WindowEvaluationServiceTest.rejectsFutureDatedWindowBeforePersisting`:** `WindowEvaluationService.toCommand(...)` previously clamped `processedAt` up to `windowStart`, bypassing Stage 3 future-window rejection. Removed clamping so raw `processedAt` is validated by `PredictionService`.
2. **`InferenceJobServiceTest.nonRetryableFailureImmediatelyWritesTerminalErrorWithoutRetries` & `SlidingWindowSchedulerIntegrationTest.handlesFullRetryMatrix...` (line 436):** `InferenceJobService.recordNonRetryableFailure` initialized new jobs with `FAILED_EXHAUSTED` before checking whether the job was already terminal, causing immediate early return before `markExhausted`, `save`, or `PredictionService.recordPrediction(ERROR)`. Fixed by checking terminal states only when `existingOpt.isPresent()` and wrapping persistence in programmatic `TransactionTemplate`.
3. **`SlidingWindowSchedulerIntegrationTest.recoversFromMidHttpCrash...` (line 505):** (a) `SlidingWindowScheduler` needed `haltedSessionsThisTick` + in-lock `expectedNextEnd` verification so later rounds cannot advance past a crashed earlier window; (b) test rollback retries (`63s, 93s, 123s`) placed attempt 3 `nextRetryAt` at `213s`, so restart recovery tick was aligned cleanly to `215s` (past the `90s` backoff) after verifying uncommitted halt at `200s`. Added explicit `concurrentSchedulerTicksNeverDuplicateWorkForSameSessionAndWindow` coverage.

---

## 7. Verified Test Evidence

### Complete Java Maven Suite (`somnisense_test_db`)

Executed after all final edits via:
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\qa\run_stage2_backend_tests.ps1
```

Reconciled across all **28 Surefire XML reports** under `backend/target/surefire-reports` (timestamps `20:50:34`–`20:51:22`):

| Suite Scope | Classes / Cases | Result |
|---|---|---|
| Total Backend Suite (`somnisense_test_db`) | **28 classes / 197 tests** | **197 passed, 0 failed, 0 errors, 0 skipped** (`BUILD SUCCESS`) |
| `SlidingWindowSchedulerIntegrationTest` | 6 tests | **6/6 passed** (`9.876 s`) |
| `AiSidecarClientRetryTest` | 2 tests | **2/2 passed** (`1.273 s`) |
| `WindowEvaluationServiceTest` (Stage 3 compat) | 5 tests | **5/5 passed** (`1.367 s`) |
| `InferenceJobServiceTest` (Milestone 4A) | 3 tests | **3/3 passed** (`0.333 s`) |
| `SimulationMigrationV6AndFencingTest` (Milestone 4A) | 5 tests | **5/5 passed** (`0.731 s`) |
| Remaining Stage 1–3 Suites (`BackendApplicationTests`, Ingestion, Sessions, Predictions, Auth, Users, Patients, Devices, Assignments) | 176 tests | **176/176 passed** |

Flyway `V1`–`V6` validated cleanly (`schema_version` up to date; Hibernate `ddl-auto=validate` passed).

### Python AI Sidecar Suite (`ai-service/tests`)

- **Initial Sandbox Failure Cause Recorded:** The first `pytest` call during Milestone 4B ran in standard sandbox mode (`BypassSandbox: false`), where access to the external workspace Python interpreter `C:\Users\LORD\Downloads\Minor_Project\SomniSense\.venv\Scripts\python.exe` failed with `CommandNotFoundException` / sandbox boundary denial. It did **not** indicate a code defect.
- **Final Verified Rerun (`BypassSandbox: true`):**
  ```powershell
  C:\Users\LORD\Downloads\Minor_Project\SomniSense\.venv\Scripts\python.exe -m pytest ai-service/tests -v
  ```
  Result: **`7 passed, 1 Starlette httpx deprecation warning in 1.70s`**.
- **Protected Artifact SHA-256 Verification:**
  - `model_c_pipeline.joblib`: `FAEF00B08C6406BB0277C6CA05F15239ACFC8365561D9C9A07EBA3554939A6CA` (unchanged)
  - `feature_schema.json`: `C4D1B2E26A15569CEF6328D8E4D06F5DC5677F0D8B74F709F30C104715D472F7` (unchanged)

---

## 8. Non-Blocking Environment Notes & Known Limitations

- **Non-blocking PostgreSQL 19 / Flyway Warning:** Booting Spring against PostgreSQL 19 logs Flyway's standard version verification notice (`Flyway 10` tested against PostgreSQL `<= 18`); all V1–V6 migrations and constraints operate normally on PostgreSQL 19.
- **Non-blocking Maven Metadata 401 Warnings:** Maven Central / Flyway external commercial metadata repositories (`flyway-community-db-support`, `Redgate MavenFeed`) return optional `HTTP 401` on `/.meta/prefixes.txt` lookups before resolving open-source artifacts cleanly from `central`.
- **Intentional Crash Test Logs:** `SlidingWindowSchedulerIntegrationTest.recoversFromMidHttpCrashWithoutSkippingCursorAndRollsBackAtomicOnPersistenceFailure` deliberately injects `new Error("simulated JVM crash during Window 1 HTTP")` and unmocked Mockito null outcomes to test the non-skipping cursor guard; the resulting `SlidingWindowScheduler` ERROR stack traces in Maven console output are expected test stimuli.
- **Remaining Stage 4 Milestones (Not Started):**
  - **Milestone 4C (`simulator-worker/`):** Standalone Python simulator worker streaming 1 Hz S1–S3 batches via `/api/v1/ingestion/readings`.
  - **Milestone 4D (`Dashboard` & Frontend):** `GET /api/v1/dashboard/summary` and React prediction/live-monitoring UI updates.
