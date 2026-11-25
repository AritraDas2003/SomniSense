# SOMNISENSE — STAGE 2 PRE-IMPLEMENTATION AUDIT
## Prediction Persistence, Flyway V5 Migration & Retrieval API Design

> **Audit Date:** 2026-11-26  
> **Branch:** `stage2/prediction-persistence` (branched from `stage1/frontend-spring-boot-integration` @ `5baaefb254e059386d6fa2fdf19350146e3fc1a4`)  
> **Authoritative Workspace:** `C:\Users\LORD\Downloads\Minor_Project\SomniSense_Team`  
> **Status:** **AUDIT COMPLETE — NO CRITICAL DESIGN CONFLICTS**

---

## 1. Executive Audit Summary

A complete inspection of the `SomniSense_Team/backend` source code, Flyway migrations (`V1`–`V4`), Hibernate configuration (`ddl-auto=validate`), Spring Security chain, error handling, and frontend mock/service contracts confirms that the `predictions` table already exists in `V1__baseline.sql` with all required columns and foreign key references. No `prediction` Java package or `simulation_runs` table exists yet.

Two existing test-support invariants were identified during the audit and must be updated alongside V5:
1. `BackendApplicationTests.flywayCreatedExactlyTheTenApprovedTables()` explicitly asserts the exact sorted table list in `public`. Adding `simulation_runs` in V5 brings the table count to 11.
2. `TestDatabase.clean(JdbcTemplate)` executes a single `TRUNCATE TABLE ...` statement across all tables. Because `simulation_runs.session_id` references `monitoring_sessions(id)`, `simulation_runs` must be added to `TestDatabase.clean(...)`.

---

## 2. Actual `predictions` Table Schema & Constraints (`V1__baseline.sql`)

Verified from `backend/src/main/resources/db/migration/V1__baseline.sql` (lines 102–115, 167):

| SQL Column | PostgreSQL Type | Nullable | Constraints / Notes |
|---|---|---|---|
| `id` | `uuid` | `NOT NULL` | `PRIMARY KEY` (assigned in Java via `AssignedIdEntity`) |
| `session_id` | `uuid` | `NOT NULL` | `FOREIGN KEY ("session_id") REFERENCES "monitoring_sessions" ("id") DEFERRABLE INITIALLY IMMEDIATE` |
| `model_version` | `varchar(50)` | `NOT NULL` | e.g. `phase7_model_c:1.0.0` |
| `window_start` | `timestamptz` | `NOT NULL` | UTC timestamp (Hibernate `jdbc.time_zone=UTC`) |
| `window_end` | `timestamptz` | `NOT NULL` | UTC timestamp |
| `predicted_class` | `varchar(40)` | `NULL` | Nullable for `REJECTED` / `ERROR` predictions |
| `confidence_score` | `numeric(7,6)` | `NULL` | Positive-class probability in `[0.000000, 1.000000]`; nullable for `REJECTED` / `ERROR` |
| `prediction_status` | `varchar(30)` | `NOT NULL` | Status enum (`COMPLETED`, `NEEDS_REVIEW`, `REJECTED`, `ERROR`) |
| `input_quality_status` | `varchar(30)` | `NULL` | Quality enum (`VALID`, `DEGRADED`, `REJECTED`) |
| `processed_at` | `timestamptz` | `NOT NULL` | UTC timestamp when inference completed/failed |
| `processing_time_ms` | `int` | `NULL` | Non-negative processing latency in milliseconds |
| `error_message` | `text` | `NULL` | Diagnostic/rejection detail without stack traces or PHI |

**Existing Indexes on `predictions`:** Only the primary key index on `id`. No unique window index or session/time lookup index exists yet.

---

## 3. Proposed Flyway Migration V5 (`V5__predictions_indexes_and_simulator_runs.sql`)

Migrations `V1`–`V4` remain **strictly unmodified**.

### A. Predictions Indexes
1. **Pre-index Duplicate Guard:** Execute a PostgreSQL `DO $$ ... END $$;` verification block checking `predictions` for any duplicate `(session_id, model_version, window_start, window_end)` tuples and raising an exception if found, without silently deleting or rewriting any existing rows.
2. **Unique Index:**
   ```sql
   CREATE UNIQUE INDEX ux_predictions_session_model_window
       ON predictions (session_id, model_version, window_start, window_end);
   ```
3. **Lookup Index:**
   ```sql
   CREATE INDEX ix_predictions_session_window_end
       ON predictions (session_id, window_end DESC);
   ```

### B. `simulation_runs` Metadata Table
Stores per-session virtual simulator run configuration and progress metadata (no ground-truth apnea annotations, no simulator execution in Stage 2):

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY` |
| `session_id` | `uuid` | `UNIQUE NOT NULL`, `FOREIGN KEY ("session_id") REFERENCES "monitoring_sessions" ("id") DEFERRABLE INITIALLY IMMEDIATE` |
| `scenario_id` | `varchar(60)` | `NOT NULL`, `CHECK (btrim(scenario_id) <> '')` |
| `seed` | `bigint` | `NOT NULL` |
| `cadence_hz` | `numeric(6,2)` | `NOT NULL`, `CHECK (cadence_hz > 0)` |
| `playback_speed` | `numeric(6,2)` | `NOT NULL`, `CHECK (playback_speed > 0)` |
| `quality_preset` | `varchar(60)` | `NULL` |
| `status` | `varchar(20)` | `NOT NULL`, `CHECK (status IN ('PENDING', 'RUNNING', 'PAUSED', 'COMPLETED', 'STOPPED', 'FAILED'))` |
| `last_step` | `bigint` | `NOT NULL DEFAULT 0`, `CHECK (last_step >= 0)` |
| `started_at` | `timestamptz` | `NOT NULL` |
| `updated_at` | `timestamptz` | `NOT NULL`, `CHECK (updated_at >= started_at)` |

Additional lookup index: `ix_simulation_runs_status` on `(status)` for future active-run recovery/queries.

**Lifecycle Semantics:**
- At most one `simulation_runs` row per `monitoring_sessions` row (`UNIQUE` on `session_id`).
- `PENDING` -> `RUNNING` <-> `PAUSED` -> terminal states `COMPLETED`, `STOPPED`, `FAILED`.
- `last_step` monotonically tracks emitted step index (`>= 0`) for deterministic resume/idempotency in later stages.
- Ground-truth labels from simulator S1–S3 must **never** be persisted in `simulation_runs`.

---

## 4. Required Java Domain Components (`com.somnisense.backend.prediction`)

Following repository conventions (`AssignedIdEntity`, `@Immutable` for append-only telemetry/inference rows, constructor/factory validation, UTC `Instant`, `BigDecimal`, `PageResponse`, `ApiException`):

1. **Enums (`entity/`):**
   - `PredictedClass`: `APNEA_EVENT`, `NORMAL`
   - `PredictionStatus`: `COMPLETED`, `NEEDS_REVIEW`, `REJECTED`, `ERROR`
     - Aligned with frontend Stage 1 `StatusBadge` / `mockMonitoringService` (`NEEDS_REVIEW`) and future Model C pipeline outcomes (`COMPLETED` for normal windows, `NEEDS_REVIEW` for apnea events, `REJECTED` for rejected windows, `ERROR` for inference failures).
   - `InputQualityStatus`: `VALID`, `DEGRADED`, `REJECTED`
2. **Entity (`entity/Prediction.java`):**
   - `@Entity`, `@Immutable`, `@Table(name = "predictions")`, `extends AssignedIdEntity`.
   - Exact column mappings matching `V1__baseline.sql` for Hibernate `ddl-auto=validate`.
3. **Repository (`repository/PredictionRepository.java`):**
   - `findBySessionIdAndWindowEndGreaterThanEqualAndWindowEndLessThan(UUID sessionId, Instant from, Instant to, Pageable pageable)` (or JPQL `findWindow`)
   - `findFirstBySessionIdOrderByWindowEndDescProcessedAtDescIdDesc(UUID sessionId)`
   - `findBySessionIdAndModelVersionAndWindowStartAndWindowEnd(UUID sessionId, String modelVersion, Instant windowStart, Instant windowEnd)`
4. **DTOs (`dto/`):**
   - `RecordPredictionCommand`: immutable service input command for internal Spring Boot persistence calls (not exposed as a public POST endpoint).
   - `RecordPredictionResult`: `{ PredictionResponse prediction, boolean created }`.
   - `PredictionResponse`: `@JsonPropertyOrder` DTO exposing all 12 prediction fields.
   - `LatestPredictionResponse`: `@JsonPropertyOrder({"sessionId", "sessionStatus", "hasPrediction", "latest"})` DTO for `/latest`.
5. **Service (`service/PredictionService.java`):**
   - `recordPrediction(RecordPredictionCommand command)` (`@Transactional`):
     - Validates non-null command, non-blank `modelVersion` (`<= 50` chars), `windowStart < windowEnd`, `processedAt >= windowStart`, `processingTimeMs == null || processingTimeMs >= 0`.
     - Verifies session exists (`ResourceNotFoundException` if absent) and is not `CANCELLED` (`ApiException(ErrorCode.CONFLICT, ...)` if cancelled).
     - Enforces status-specific invariants:
       - `COMPLETED` / `NEEDS_REVIEW`: requires non-null `predictedClass`, `confidenceScore` in `[0, 1]` (normalized to scale 6 `HALF_UP`), and `inputQualityStatus != REJECTED`.
       - `REJECTED`: requires `predictedClass == null`, `confidenceScore == null`, `inputQualityStatus == REJECTED` (or null disallowed/normalized), preventing fabricated class/confidence.
       - `ERROR`: requires `predictedClass == null`, `confidenceScore == null`, and non-blank `errorMessage`.
     - Handles duplicate `(sessionId, modelVersion, windowStart, windowEnd)` idempotently via pre-check + `DataIntegrityViolationException` catch-and-reload on `saveAndFlush`, returning `RecordPredictionResult(existing, false)`.
   - `list(UUID sessionId, Instant from, Instant to, int page, int size)` (`@Transactional(readOnly = true)`):
     - Validates session existence (`404` if missing) and `from < to` when both provided (`400 INVALID_REQUEST`).
     - Queries `sessionId` with `windowEnd >= start` and `windowEnd < end`, sorted newest-first (`windowEnd DESC, processedAt DESC, id ASC`).
   - `latest(UUID sessionId)` (`@Transactional(readOnly = true)`):
     - Validates session existence (`404` if missing).
     - Returns `LatestPredictionResponse(session.getId(), session.getStatus().name(), latest != null, latest)`.
6. **Controller (`controller/PredictionController.java`):**
   - `@RestController`, `@RequestMapping("/api/v1/sessions/{sessionId}/predictions")`, `@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")`.
   - `GET /api/v1/sessions/{sessionId}/predictions`
   - `GET /api/v1/sessions/{sessionId}/predictions/latest`

---

## 5. Endpoint Contracts & No-Prediction Behaviour

### Endpoint A: `GET /api/v1/sessions/{sessionId}/predictions`
- **Query parameters:**
  - `from` (optional, ISO-8601 UTC `Instant`, inclusive on `windowEnd`)
  - `to` (optional, ISO-8601 UTC `Instant`, exclusive on `windowEnd`)
  - `page` (optional, default `0`, `@Min(0)`)
  - `size` (optional, default `20`, `@Min(1) @Max(100)`)
- **Sort order:** Deterministic newest-first (`windowEnd DESC`, `processedAt DESC`, `id ASC`).
- **Response (`200 OK`):** Standard `PageResponse<PredictionResponse>` (`{content, page, size, totalElements, totalPages}`).
- **Errors:** `400 VALIDATION_FAILED` (`page < 0`, `size < 1`, `size > 100`), `400 INVALID_REQUEST` (`from >= to`, bad UUID/timestamp), `401 UNAUTHORIZED`, `403 FORBIDDEN`, `404 RESOURCE_NOT_FOUND` (unknown `sessionId`).

### Endpoint B: `GET /api/v1/sessions/{sessionId}/predictions/latest`
- **Response (`200 OK`):** `LatestPredictionResponse`:
  - When predictions exist: `{"sessionId":"...","sessionStatus":"ACTIVE","hasPrediction":true,"latest":{...}}`.
  - When no predictions exist yet: `{"sessionId":"...","sessionStatus":"ACTIVE","hasPrediction":false,"latest":null}`.
  - Mirrors `GET /api/v1/sessions/{sessionId}/readings/latest` (`200` with `null` payload when empty, avoiding false `404` confusion with missing session and never fabricating a `NORMAL` prediction).
- **Errors:** `400 INVALID_REQUEST` (malformed `sessionId`), `401 UNAUTHORIZED`, `403 FORBIDDEN`, `404 RESOURCE_NOT_FOUND` (unknown `sessionId`).

---

## 6. RBAC & Security Verification Plan

- Security filter chain (`SecurityConfig`, `@Order(2)`): session cookie (`JSESSIONID`) + `anyRequest().authenticated()`.
- Method security (`@EnableMethodSecurity`): `@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")` on `PredictionController`.
- Unauthenticated requests return `401 UNAUTHORIZED` (`ApiError` JSON + `X-Correlation-Id`).
- Any non-`ADMIN`/non-`OPERATOR` principal (tested via `@WithMockUser(roles = "GUEST")` or security test) is rejected with `403 FORBIDDEN`.
- No public `POST /predictions` endpoint is exposed in Stage 2; persistence happens exclusively through the internal `PredictionService`.
- All queries are scoped by `sessionId` after verifying session existence.

---

## 7. Testing Strategy (`somnisense_test_db`)

1. **Migration & Schema Validation:**
   - Flyway `V1`–`V5` apply cleanly to `somnisense_test_db`.
   - Hibernate `ddl-auto=validate` passes on startup.
   - `BackendApplicationTests` verifies all 11 tables (including `simulation_runs`).
   - Direct JDBC tests verify `ux_predictions_session_model_window` rejects duplicate rows and `simulation_runs` enforces `UNIQUE (session_id)`, `FK (session_id -> monitoring_sessions.id)`, and `CHECK` constraints (`cadence_hz > 0`, `playback_speed > 0`, `last_step >= 0`, valid `status`, `updated_at >= started_at`).
2. **Service Unit/Integration Tests (`PredictionServiceTest`):**
   - Valid `COMPLETED` (`NORMAL`) and `NEEDS_REVIEW` (`APNEA_EVENT`) persistence.
   - `REJECTED` and `ERROR` persistence with `null` `predictedClass` & `confidenceScore` (and rejection if caller attempts to pass fabricated class/confidence on `REJECTED`/`ERROR`).
   - Invalid confidence (`< 0`, `> 1`, or missing on `COMPLETED`), invalid window (`windowStart >= windowEnd`), invalid `modelVersion`, negative `processingTimeMs`, `processedAt < windowStart`, nonexistent session, and cancelled session rejected.
   - Duplicate `(sessionId, modelVersion, windowStart, windowEnd)` handled idempotently (`created = false`, single row in DB).
3. **REST API & Security Tests (`PredictionApiTest`):**
   - Paginated prediction history (`size`, `page`, deterministic newest-first order).
   - `from` / `to` timestamp filtering and `from >= to` `400` handling.
   - Latest prediction retrieval (`hasPrediction: true` vs no predictions yet `hasPrediction: false, latest: null`).
   - Invalid `sessionId` (`400` malformed UUID, `404` unknown UUID) and invalid query params (`size=101`, `page=-1`).
   - `ADMIN` and `OPERATOR` access succeed (`200`), unauthenticated rejected (`401`), unauthorized role rejected (`403`).
   - Session isolation (predictions from session A never appear in session B queries).

---

## 8. Risks & Compatibility Notes

1. **Database Credentials for Tests:** `application.properties` requires `DB_PASSWORD` (via environment variable or git-ignored `backend/.env`) and PostgreSQL `somnisense_test_db` to execute `./mvnw.cmd test`.
2. **Frontend Stage 1 Isolation:** Stage 1 frontend (`frontend/src/services/patientService.js`) intentionally leaves `predictions: []` and `mlAvailable: false` until Stage 3; Stage 2 does not alter frontend files.
3. **Protected Assets:** `V1`–`V4`, Model C artifacts (`phase7_model_c`), Simulator S1–S3 original workspace, and `SomniSense/` prototype remain strictly untouched.
