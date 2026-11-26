# SOMNISENSE — Stage 4 Milestone 4C Implementation & Verification Report

**Branch:** `stage4/continuous-monitoring-simulator`  
**Specification Baseline:** [STAGE4_PRE_IMPLEMENTATION_AUDIT.md](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/STAGE4_PRE_IMPLEMENTATION_AUDIT.md) (v2.3)  
**Prior Milestone Reports:** [STAGE4_MILESTONE_4A_REPORT.md](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/STAGE4_MILESTONE_4A_REPORT.md), [STAGE4_MILESTONE_4B_REPORT.md](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/STAGE4_MILESTONE_4B_REPORT.md)  
**Scope:** Milestone 4C (Standalone Python `simulator-worker` service implementing S1–S3 physiological simulation, fencing/lease compliance, retry/backpressure handling, and HTTP integration/security regression verification).  
**Status:** Implemented, database- and Python-verified (`198/198` Maven tests on `somnisense_test_db`, `10/10` `simulator-worker` pytest tests), ready for publication. **Milestone 4D has not started.**

---

## 1. Delivered Components

Added standalone Python service under [simulator-worker/](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/simulator-worker/README.md) plus end-to-end backend HTTP/CSRF/RBAC regression test [SimulationControllerIntegrationTest.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/test/java/com/somnisense/backend/simulation/SimulationControllerIntegrationTest.java):

- **FastAPI Control API & Schemas ([main.py](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/simulator-worker/app/main.py), [schemas.py](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/simulator-worker/app/schemas.py), [config.py](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/simulator-worker/app/config.py)):**
  - `GET /internal/sim/health` (unauthenticated health probe reporting `databaseAccess: false`, `workerInstanceId`, `activeRuns`)
  - `GET /internal/sim/ready` (verifies `>= 32`-character shared internal and ingestion keys; returns `503` otherwise)
  - Authenticated control endpoints protected by `X-Internal-Sim-Key` (`hmac.compare_digest`) with `X-Correlation-Id` propagation:
    - `POST /internal/sim/start`
    - `POST /internal/sim/resume`
    - `POST /internal/sim/pause`
    - `POST /internal/sim/stop`
    - `GET /internal/sim/status` (`?sessionId=...`)
- **Ported S1–S3 Simulation Engine (`simulator-worker/app/engine/`):**
  - [rng.py](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/simulator-worker/app/engine/rng.py): Deterministic 3-stream `SeedSequence` + `PCG64` RNG (`events`, `physiology`, `noise`).
  - [scenarios.py](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/simulator-worker/app/engine/scenarios.py): Presets `normal_sleep`, `severe_osa`, `cheyne_stokes_csa`, `moderate_hypopnea` (with canonical aliases) — zero ground-truth labels sent to Spring Boot or PostgreSQL.
  - [physiology.py](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/simulator-worker/app/engine/physiology.py): Cardiopulmonary oxygen kinetics (251-entry circular lung-to-finger transit delay buffer) and autonomic hemodynamic coupling (RSA, vagal bradycardia, sympathetic post-arousal surge).
  - [degradation.py](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/simulator-worker/app/engine/degradation.py): Stream-C quality degradation presets (`CLEAN`, `DEFAULT`, `NOISY`, `TRANSIENT_DROPOUT`, `DEGRADED`, `PROLONGED_DISCONNECT`) emitting `null` vitals on dropouts while preserving physiological bounds (`SpO2` in `[40.0, 100.0]`, `HR` in `[20.0, 250.0]`).
  - [core.py](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/simulator-worker/app/engine/core.py): 1-based step generation (`step_number = step_index + 1`) and `fast_forward_before_step(start_step)` warm-up so resume from `last_step + 1` matches an uninterrupted run bit-for-bit.
- **Lifecycle, Fencing, Lease Keepalive & Backpressure ([worker_manager.py](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/simulator-worker/app/worker_manager.py)):**
  - Submits bounded batches (`SIM_BATCH_SIZE` 1–600, `SIM_FLUSH_INTERVAL_SECONDS`) exclusively to Spring Boot `POST /api/v1/ingestion/readings` with `X-Ingestion-Key`, `simulationRunId`, `workerGeneration`, `workerInstanceId`, deterministic `readingKey = "{DEVICECODE}-{SESSIONCODE}-{step:08d}"`, and `signalQuality: null`.
  - Enforces playback pacing without crossing Spring Boot's `10s` `MEASURED_AT_IN_FUTURE` window.
  - Self-terminates immediately (`FENCED_STOPPED`) with zero retries upon `401`, `403`, `404`, or `409` fencing responses; halts on `LEASE_EXPIRED`; retries transient `429`/`5xx`/network errors with exponential backoff preserving identical batch keys; guards older-generation `/pause`/`/stop` commands from halting newer generations; cancels all active tasks cleanly in FastAPI lifespan teardown.

---

## 2. Architectural & Security Verification

- **Database & Model Isolation:** Verified `simulator-worker` contains zero PostgreSQL driver imports (`psycopg`, `asyncpg`, `sqlalchemy`, `pg8000`), holds no DB credentials (`backend/.env` and `simulator-worker/.env` do not exist / are never tracked), and never accesses frozen Model C artifacts or `/internal/ai/*`.
- **Backend Production Security Preserved:** [SecurityConfig.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/security/SecurityConfig.java), [CsrfCookieFilter.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/security/CsrfCookieFilter.java), and [ActiveUserCheckFilter.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/security/ActiveUserCheckFilter.java) have zero modifications.
- **CSRF Test Context Finding & Resolution:** During Milestone 4C development, importing and invoking Spring Security's `SecurityMockMvcRequestPostProcessors.csrf()` inside `SimulationControllerIntegrationTest` called `WebTestUtils.setCsrfTokenRepository(...)` on the shared singleton `CsrfFilter`, temporarily replacing `CookieCsrfTokenRepository` (`XSRF-TOKEN`) with `TestCsrfTokenRepository(HttpSessionCsrfTokenRepository)` (`X-CSRF-TOKEN`). That caused line 141 of `SimulationControllerIntegrationTest` and all `18` `UserManagementTest` logins in the shared context to fail (`179/198`). Removing `.with(csrf())` and driving real `GET /api/v1/auth/csrf` -> `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header flows resolved all `19` failures without disabling or weakening CSRF, authentication, or RBAC.
- **Model C Artifact SHA-256 Verification (All Unchanged):**
  - `model_c_pipeline.joblib`: `FAEF00B08C6406BB0277C6CA05F15239ACFC8365561D9C9A07EBA3554939A6CA`
  - `feature_schema.json`: `C4D1B2E26A15569CEF6328D8E4D06F5DC5677F0D8B74F709F30C104715D472F7`
  - `inference.py`: `EEAE1F80AFF0CC477398CD7B1A3D9BE3C7E5D2B4C8EE775E1EDD284BD87928BB`
  - `model_metadata.json`: `02304CC6C5FA958D818BF899AB067C74D01C7D1A97580C63BAF2AB669CF5A714`

---

## 3. Verified Test Evidence

### Backend QA Suite (`somnisense_test_db`, Flyway `V1`–`V6` Validated)
Executed via `.\scripts\qa\run_stage2_backend_tests.ps1` and reconciled across all **29 Surefire XML reports** (`backend/target/surefire-reports`):

| Metric / Suite | Result |
|---|---|
| Total Backend Suite (**29 test classes**) | **198 tests, 198 passed, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`) |
| `SimulationControllerIntegrationTest` | **1/1 passed** (`0.321 s`) — missing/invalid CSRF `403 CSRF_TOKEN_INVALID`, unauth `401`, `GUEST` `403`, `OPERATOR`/`ADMIN` start->ingestion->pause->resume->stale `409`->stop |
| `SimulationMigrationV6AndFencingTest` | **5/5 passed** (`0.694 s`) |
| `UserManagementTest` | **18/18 passed** (`4.546 s`) |
| `AuthFlowTest` | **18/18 passed** (`4.166 s`) |
| `SlidingWindowSchedulerIntegrationTest` & `AiSidecarClientRetryTest` | **6/6** (`9.196 s`) & **2/2** (`1.256 s`) |
| All Remaining Stage 1–3 Suites | **148/148 passed** |

### Python Simulator Worker Suite (`simulator-worker/tests`)
Executed via `C:\Users\LORD\Downloads\Minor_Project\SomniSense\.venv\Scripts\python.exe -m pytest tests -v`:
- **Result:** **`10 passed, 1 Starlette httpx deprecation warning in 4.76s`** (0 failed, 0 skipped, exit code `0`).
  - `test_engine_s1_s3.py` (`4/4`): seeded determinism + `fast_forward_before_step` bit-exact resume, S2 scenarios (`normal_sleep`, `severe_osa`, `cheyne_stokes_csa`, `moderate_hypopnea`), S3 quality presets/dropouts/artifacts, zero DB/Model-C dependency scan.
  - `test_worker_lifecycle_and_fencing.py` (`6/6`): `/health` (`databaseAccess: false`), `/ready`, `X-Internal-Sim-Key` auth & `X-Correlation-Id`, `start -> pause -> resume` with older-gen protection, HTTP control endpoints & graceful lifespan shutdown, immediate `409` fencing termination without retries, `ReadTimeout`/`503` retry preserving batch keys, and lease expiration halt.
- *(Note: Earlier in Milestone 4C development, `test_start_pause_resume_continuity_and_older_gen_protection` initially failed `1/9` in test setup timing because a `12s` catch-up window finished before `/pause`, leaving gen-2 waiting in real-time behind a `0.25s` test sleep; increasing the initial catch-up span to `40s` with bounded poll waits resolved that test setup cleanly.)*

---

## 4. Known Warnings, Limitations & Scope Boundary

- **Non-Blocking Warnings:** PostgreSQL 19 logs Flyway's version verification notice (`<= 18`); Maven central resolves cleanly after optional third-party commercial repository `401` metadata checks; FastAPI `TestClient` emits a Starlette `httpx2` deprecation warning.
- **Validation Scope:** Evidence covers automated Python unit/async/HTTP tests and `MockMvc` + stub-server integration on `somnisense_test_db`. Clinical/hardware validation and live browser multi-patient E2E testing are not claimed at Milestone 4C.
- **Milestone 4D Status:** Dashboard summary (`GET /api/v1/dashboard/summary`) and React live prediction UI wiring have **not started** and belong strictly to Milestone 4D.
