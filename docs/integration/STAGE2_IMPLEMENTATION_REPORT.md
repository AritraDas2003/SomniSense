# SOMNISENSE — STAGE 2 IMPLEMENTATION REPORT
## Prediction Persistence, Flyway V5 Migration & Staff Retrieval APIs

> **Date:** 2026-11-26  
> **Branch:** `stage2/prediction-persistence` (branched from `stage1/frontend-spring-boot-integration` @ `5baaefb254e059386d6fa2fdf19350146e3fc1a4`)  
> **Implementation Commit:** `187e4bb1a4252b6cfdf439679f8a5b5e7a096b77` (`feat(backend): add prediction persistence and APIs`)  
> **Authoritative Workspace:** `C:\Users\LORD\Downloads\Minor_Project\SomniSense_Team`  
> **Team Remote (`origin`):** `https://github.com/AritraDas2003/SomniSense` (branch `stage2/prediction-persistence`)  
> **Personal Remote (`personal`):** `https://github.com/lordsins000/SomniSense` (branch `stage2/prediction-persistence`)  
> **Status:** **VERIFIED & PUBLISHED** (176/176 Maven tests passing, 0 failures, 0 errors, 0 skipped)

---

## 1. Architecture Decisions

1. **Spring Boot as Sole Database Owner:** All persistence, schema validation (`ddl-auto=validate`), Flyway migrations, and authorization remain inside Spring Boot. No Python FastAPI sidecar or ML inference engine was added in Stage 2.
2. **Internal-Only Prediction Persistence:** `PredictionService.recordPrediction(RecordPredictionCommand)` provides validated persistence for future Stage 3 internal orchestration. No public `POST /predictions` endpoint is exposed to browser clients.
3. **Model C Contract Alignment:**
   - `PredictedClass`: `APNEA_EVENT`, `NORMAL` (nullable for `REJECTED` / `ERROR`).
   - `PredictionStatus`: `COMPLETED`, `NEEDS_REVIEW`, `REJECTED`, `ERROR` (aligned with Stage 1 frontend `NEEDS_REVIEW` badges and future Model C failure/rejection states).
   - `InputQualityStatus`: `VALID`, `DEGRADED`, `REJECTED`.
   - `confidenceScore`: `BigDecimal` scaled to `numeric(7,6)` in `[0, 1]`.
4. **No-Prediction-Yet Semantics:** `GET /api/v1/sessions/{sessionId}/predictions/latest` returns `200 OK` with `{"sessionId": ..., "sessionStatus": ..., "hasPrediction": false, "latest": null}` when a session exists but has no predictions yet, mirroring `/readings/latest` and never fabricating a `NORMAL` result.
5. **Idempotent Window Persistence:** Duplicate `(sessionId, modelVersion, windowStart, windowEnd)` writes are handled idempotently (pre-lookup + `TransactionTemplate` `saveAndFlush` with `DataIntegrityViolationException` fallback lookup), returning `created = false` with the existing persisted record.

---

## 2. Flyway V5 Schema Changes

Added `backend/src/main/resources/db/migration/V5__predictions_indexes_and_simulator_runs.sql` (`V1`–`V4` strictly untouched):

- **Pre-Index Duplicate Guard:** `DO $$ ... END $$;` checks `predictions` for duplicate `(session_id, model_version, window_start, window_end)` tuples and fails explicitly if found, without silently deleting or rewriting records.
- **Predictions Indexes:**
  - `ux_predictions_session_model_window` (`UNIQUE`) on `(session_id, model_version, window_start, window_end)`
  - `ix_predictions_session_window_end` on `(session_id, window_end DESC)`
- **`simulation_runs` Table:**
  - `id` (`uuid PRIMARY KEY`), `session_id` (`uuid UNIQUE NOT NULL`, FK to `monitoring_sessions(id)` `DEFERRABLE INITIALLY IMMEDIATE`), `scenario_id` (`varchar(60) NOT NULL`), `seed` (`bigint NOT NULL`), `cadence_hz` (`numeric(6,2) NOT NULL`), `playback_speed` (`numeric(6,2) NOT NULL`), `quality_preset` (`varchar(60)` nullable), `status` (`varchar(20) NOT NULL`), `last_step` (`bigint NOT NULL DEFAULT 0`), `started_at` (`timestamptz NOT NULL`), `updated_at` (`timestamptz NOT NULL`).
  - `CHECK` constraints enforce non-blank `scenario_id`, positive `cadence_hz` and `playback_speed`, valid `status` (`PENDING`, `RUNNING`, `PAUSED`, `COMPLETED`, `STOPPED`, `FAILED`), `last_step >= 0`, and `updated_at >= started_at`.
  - Lookup index `ix_simulation_runs_status` on `(status)`.
  - No simulator ground-truth annotations are stored.

---

## 3. Java & QA Components Added/Updated

- **Domain & Persistence (`com.somnisense.backend.prediction`):**
  - `entity/Prediction.java`, `entity/PredictedClass.java`, `entity/PredictionStatus.java`, `entity/InputQualityStatus.java`
  - `repository/PredictionRepository.java`
  - `dto/PredictionResponse.java`, `dto/LatestPredictionResponse.java`, `dto/RecordPredictionCommand.java`, `dto/RecordPredictionResult.java`
  - `service/PredictionService.java`
  - `controller/PredictionController.java`
- **API Documentation:**
  - `backend/docs/api/PREDICTIONS.md`
  - `backend/docs/api/README.md`
- **Test Support & Tests:**
  - `backend/src/test/java/com/somnisense/backend/BackendApplicationTests.java` (updated table assertion from 10 to 11 approved tables including `simulation_runs`)
  - `backend/src/test/java/com/somnisense/backend/support/TestDatabase.java` (added `simulation_runs` to `TRUNCATE TABLE` in `somnisense_test_db`)
  - `backend/src/test/java/com/somnisense/backend/prediction/PredictionMigrationAndConstraintsTest.java` (3 tests)
  - `backend/src/test/java/com/somnisense/backend/prediction/PredictionServiceTest.java` (5 tests)
  - `backend/src/test/java/com/somnisense/backend/prediction/PredictionApiTest.java` (3 tests)
- **Interactive QA Launcher:**
  - `scripts/qa/run_stage2_backend_tests.ps1` (prompts privately via `Read-Host -AsSecureString`, passes credentials only to child process environments, validates/creates `somnisense_test_db` without touching `somnisense_db`, cleans up BSTR/SecureString/env vars in `finally`).

---

## 4. API Contracts Delivered

- `GET /api/v1/sessions/{sessionId}/predictions?from=&to=&page=0&size=20`
  - Bounded pagination (`size` `1..100`, default `20`), optional ISO-8601 UTC `from` (inclusive) and `to` (exclusive) filtering on `windowEnd`, deterministic newest-first sorting (`windowEnd DESC`, `processedAt DESC`, `id ASC`), standard `PageResponse<PredictionResponse>`.
- `GET /api/v1/sessions/{sessionId}/predictions/latest`
  - Returns `LatestPredictionResponse` (`200 OK` with `hasPrediction: false, latest: null` when no predictions exist yet; `404 RESOURCE_NOT_FOUND` only when `sessionId` does not exist).

---

## 5. Automated Test Results (`somnisense_test_db`)

Executed via `.\scripts\qa\run_stage2_backend_tests.ps1` (`.\mvnw.cmd test` with `@ActiveProfiles("test")` against `somnisense_test_db`):

- **Total:** **176 passed, 0 failed, 0 errors, 0 skipped** across **23 test classes** (`BUILD SUCCESS`).
- **Stage 2 Prediction Suites:**
  - `PredictionMigrationAndConstraintsTest`: `3/3` passed (Flyway `V1`–`V5` applied, indexes verified, `ux_predictions_session_model_window` duplicate rejection verified, `simulation_runs` FK/uniqueness/`CHECK` constraints verified).
  - `PredictionServiceTest`: `5/5` passed (valid `COMPLETED`/`NEEDS_REVIEW`, `REJECTED`/`ERROR` without fabricated values, invalid confidence/window/timestamps/modelVersion rejected, nonexistent/cancelled sessions rejected, idempotent duplicate handling verified).
  - `PredictionApiTest`: `3/3` passed (pagination, newest-first order, `from`/`to` filtering, session scoping, `/latest` with and without predictions, `400`/`404`, `ADMIN`/`OPERATOR` access, `401` unauthenticated, `403` unauthorized role).
  - `BackendApplicationTests`: `3/3` passed (`contextLoads`, `somnisense_test_db` check, 11-table schema check, Hibernate `ddl-auto=validate`).
- **Observed Non-Fatal Environment Warnings:**
  - Flyway logged its standard compatibility warning for PostgreSQL 19 (newer than Flyway's tested baseline version); all `V1`–`V5` migrations and `ddl-auto=validate` succeeded.
  - Maven logged `HTTP Status: 401` metadata warnings for optional Redgate/Flyway remote feeds before resolving all artifacts from Maven Central.

---

## 6. Security & Protected-File Integrity Verification

- **RBAC:** `PredictionController` enforces `@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")` on top of `SecurityConfig`'s authenticated session chain. Unauthenticated calls return `401 UNAUTHORIZED`; unauthorized roles return `403 FORBIDDEN`.
- **Session Scoping:** Both endpoints verify `sessionId` exists and filter strictly by `sessionId`.
- **No Secret Leakage:** `ApiError` responses omit stack traces and SQL details; `run_stage2_backend_tests.ps1` never prints, logs, persists, or passes passwords via CLI arguments.
- **Protected Assets Untouched:**
  - Flyway `V1`–`V4`: 0 changes.
  - Frozen Model C artifacts (`phase7_model_c`), feature schema, and threshold (`0.50`): untouched.
  - Original `SomniSense` prototype and Simulator S1–S3: untouched.
  - Stage 1 React frontend (`frontend/`): untouched.

---

## 7. Remaining Stage 3 Work & Limitations

- **Live Model C Inference:** No Python FastAPI sidecar, model loading, feature extraction, or inference scheduler exists yet; these belong to Stage 3.
- **Simulator Execution:** `simulation_runs` schema metadata is in place, but simulator S1–S3 execution and lifecycle endpoints are deferred to later stages.
- **Frontend Integration:** `frontend/src/services/patientService.js` still awaits Stage 3 wiring to consume `/api/v1/sessions/{sessionId}/predictions` and `/latest`.
