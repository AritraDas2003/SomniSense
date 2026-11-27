# SOMNISENSE — Stage 4 Milestone 4D Implementation & Verification Report
## Dashboard Summary API & React Live Prediction UI

**Branch:** `stage4/continuous-monitoring-simulator`  
**Base Commit:** `422a0ba856fe5d9ffc399a757486b304744ba06e`  
**Specification Baselines:** [STAGE4_PRE_IMPLEMENTATION_AUDIT.md](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/STAGE4_PRE_IMPLEMENTATION_AUDIT.md) (v2.3), [STAGE4_MILESTONE_4D_PRE_IMPLEMENTATION_AUDIT.md](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/STAGE4_MILESTONE_4D_PRE_IMPLEMENTATION_AUDIT.md)  
**Scope:** Consolidated Spring Boot `GET /api/v1/dashboard/summary`, `PatientSummaryResponse.reviewStatus`, live React prediction/monitoring UI, Vitest + React Testing Library suite, and full-stack Chromium Playwright E2E validation.  
**Verdict:** **READY FOR PUBLICATION** *(uncommitted in local working tree per Milestone 4D stop conditions)*

---

## 1. Delivered Backend Implementation

### 1.1 `GET /api/v1/dashboard/summary`
- **[DashboardController.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/dashboard/controller/DashboardController.java):** Protected with `@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")`, returning `401 UNAUTHORIZED` unauthenticated and `403 FORBIDDEN` for non-staff roles without altering Spring Security/CSRF filters.
- **[DashboardSummaryResponse.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/dashboard/dto/DashboardSummaryResponse.java):**
  - Top-level fields: `generatedAt`, `modelVersion`, `summary`, `activeSessionsList` (capped at `50`), `recentPatients` (top `4`).
  - `summary` fields:
    - `patientCount`: system-wide total via `patients` count (`ACTIVE` + `INACTIVE`, Decision D2).
    - `activeSessions`: total `ACTIVE` rows in `monitoring_sessions`.
    - `activeSessionsReturned` & `activeSessionsTruncated`: explicitly expose active-list truncation when `activeSessions > 50`.
    - `itemsNeedingReview`: **Decision D1 Option A** — distinct count of `ACTIVE` sessions whose **latest** prediction for `modelVersion` (`phase7_model_c:1.0.0`) has `prediction_status = 'NEEDS_REVIEW'`.
    - `dataIssues`: derived request-time count matching [ConnectivityPolicy.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/device/service/ConnectivityPolicy.java) (`is_active = true AND last_seen_at IS NOT NULL AND last_seen_at < generatedAt - staleAfter`).
    - `activeSessionStatuses`: window-level counts across `ACTIVE` sessions (`completed`, `needsReview`, `rejected`, `error`, `degradedWindows`, `pendingRetryJobs` from `inference_window_jobs` in `RETRY_WAIT`).
- **[DashboardService.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/dashboard/service/DashboardService.java):**
  - Strictly `@Transactional(readOnly = true)` with zero dependency on `AiSidecarClient`, `SimulationWorkerClient`, or inference mutation.
  - Consolidates all scalar and active-window status aggregates into a single PostgreSQL CTE query, short-circuits on empty DB, and batch-loads active sessions (`<= 50`), recent patients (`<= 4`), assignments, devices, simulation runs, latest readings (`DISTINCT ON (session_id)`), and latest predictions (`DISTINCT ON (session_id)`).
  - **Empirically Measured SQL Query Count (Hibernate `Statistics`):** **`9` SQL prepared statements** for **55 active sessions** (bounded O(1) <= 10, zero N+1 loops).

### 1.2 `PatientSummaryResponse.reviewStatus` (Decision D3) & Batch Repositories
- Added batch queries in [PredictionRepository.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/prediction/repository/PredictionRepository.java) (`findLatestPerSession` using `DISTINCT ON (session_id)`), [PatientRepository.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/patient/repository/PatientRepository.java) (`findRecentOrdered`), [MonitoringSessionRepository.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/session/repository/MonitoringSessionRepository.java) (`findActiveOrdered`), and [DeviceAssignmentRepository.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/assignment/repository/DeviceAssignmentRepository.java) (`findForDashboardBatch`).
- Updated [PatientSummaryResponse.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/patient/dto/PatientSummaryResponse.java), [PatientService.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/main/java/com/somnisense/backend/patient/service/PatientService.java), and [PatientCodeTest.java](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/backend/src/test/java/com/somnisense/backend/patient/service/PatientCodeTest.java) so `GET /api/v1/patients` populates `reviewStatus` in one batch query (`"NONE"` when no active session or prediction exists).

---

## 2. Delivered Frontend Implementation

- **[StatusBadge.jsx](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/frontend/src/components/common/StatusBadge.jsx):** Added badges for `COMPLETED`, `REJECTED`, `ERROR`, `VALID`, `DEGRADED`, `RUNNING`, `PAUSED`, `STOPPED`, `FAILED`, `NORMAL`, and `APNEA_EVENT`.
- **[dashboardService.js](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/frontend/src/services/dashboardService.js):** Calls `GET /api/v1/dashboard/summary` in `LIVE` mode and preserves normalized `MOCK` compatibility.
- **[patientService.js](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/frontend/src/services/patientService.js):** Fetches `/predictions/latest`, `/predictions?page=0&size=20`, and `/simulation` (treating `404` as `null`), setting `mlAvailable: true`; adds CSRF-routed `startSession`, `endSession`, `cancelSession`, `startSimulation`, `pauseSimulation`, `resumeSimulation`, and `stopSimulation` helpers calling Spring Boot exclusively.
- **[DashboardPage.jsx](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/frontend/src/pages/dashboard/DashboardPage.jsx):** Removes obsolete Stage 1 `"Partial overview metrics"` banner in live mode, renders explicit `0` KPI cards, active monitoring sessions grid with window breakdown and `activeSessionsTruncated` notice, visibility-aware `10s` polling while `activeSessions > 0`, manual refresh, and non-destructive `.stale-banner` fallback on background poll failure.
- **[PatientDetailsPage.jsx](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/frontend/src/pages/patients/PatientDetailsPage.jsx) & [App.css](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/frontend/src/App.css):** Removes obsolete `"Model service unavailable"` notice when ML is active; renders latest Model C outcome (`COMPLETED`, `NEEDS_REVIEW`, `DEGRADED`/`REJECTED`, `ERROR`, or neutral `"Evaluating initial 60s baseline window..."` without ever fabricating `NORMAL`); enforces `"Model output only. This is not a diagnosis."`; renders 20-window history table, Start/End/Cancel (with inline confirmation) session controls, Start/Pause/Resume/Stop simulator controls with scenario/seed selection, visibility-aware `10s` polling strictly while `session.status === 'ACTIVE'`, and mobile-safe CSS (`overflow-x: clip` + `minmax(0, 1fr)`).

---

## 3. Isolated Verification & Evidence Summary

To run all tests autonomously without altering the user's running PostgreSQL 19 instance on port `5432`, `somnisense_db`, or `somnisense_test_db`, temporary PostgreSQL 19 clusters were initialized under `%TEMP%` on unused `127.0.0.1` ports with SCRAM-SHA-256 authentication, and stopped/deleted completely after verification.

| Check | Command / Scope | Observed Result |
|---|---|---|
| **Focused Backend Test** | `run_stage2_backend_tests.ps1 -TestFilter DashboardSummaryIntegrationTest` | **3/3 passed**, **0 failures**, **0 errors**, **0 skipped** (`somnisense_test_db`, SQL budget = **9** queries) |
| **Complete Backend QA** | `run_stage2_backend_tests.ps1` across **30 Surefire XML files** | **201/201 passed**, **0 failures**, **0 errors**, **0 skipped**, `BUILD SUCCESS` (Flyway `V1`–`V6` validated) |
| **Python AI Service** | `pytest tests -v` (`ai-service`) | **7/7 passed** (1 Starlette `httpx2` deprecation warning) |
| **Python Simulator Worker** | `pytest tests -v` (`simulator-worker`) | **10/10 passed** (1 Starlette `httpx2` deprecation warning) |
| **Vitest + RTL** | `npm test` (`StatusBadge`, `services`, `DashboardPage`, `PatientDetailsPage`) | **4/4 files passed, 10/10 tests passed** |
| **ESLint (Modified & New JS)** | `npx eslint vite.config.js src/services/... StatusBadge.jsx DashboardPage.jsx PatientDetailsPage.jsx src/test` | **0 errors, 0 warnings** *(repo-wide `npm run lint` reports 1 pre-existing error in untouched `button.jsx:56`)* |
| **Vite Builds & Bundle Scan** | `VITE_USE_MOCK_API=true` & `VITE_USE_MOCK_API=false` `npm run build` | Both built cleanly; JS bundle grep for `8090`, `8095`, `X-Internal-AI-Key`, `X-Internal-Sim-Key`, `X-Ingestion-Key` returned **0 matches** |
| **Full-Stack Playwright E2E** | [run_stage4d_e2e.ps1](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/scripts/qa/run_stage4d_e2e.ps1) + [stage4d_full_stack_e2e.py](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/scripts/qa/stage4d_full_stack_e2e.py) (`ai-service:8090`, `simulator-worker:8095`, Spring Boot `:8081`, Vite `:5173`, isolated `somnisense_e2e_db`) | **PASS (exit 0)** — `401`/`403` unauth/CSRF checks, empty dashboard, 3 simulated patients (`normal_sleep`, `severe_osa`, `moderate_hypopnea`), real `60s` Model C windows, simulator `start`/`pause`/`resume`/`stop`, `10s` polling & transient stale recovery, desktop (`1440x900`) & mobile (`390x844`, `scrollWidth == 390`) layouts, `end` & `cancel` confirmation, 0 `pageerror`/app `console.error`/`5xx` |

### Captured E2E Screenshots (`docs/integration/evidence/`)
- [stage4d_01_dashboard_empty.png](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/evidence/stage4d_01_dashboard_empty.png) — Empty live dashboard snapshot (`Total patients: 0`, `Active sessions: 0`).
- [stage4d_02_patient1_normal_live.png](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/evidence/stage4d_02_patient1_normal_live.png) — `normal_sleep` patient live readings, `COMPLETED` / `Normal` window history, and non-diagnosis notice.
- [stage4d_03_patient2_apnea_live.png](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/evidence/stage4d_03_patient2_apnea_live.png) — `severe_osa` patient desaturation waveform, `NEEDS_REVIEW` / `APNEA_EVENT` prediction (`98.2%`), and active simulator controls.
- [stage4d_04_dashboard_active_summary.png](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/evidence/stage4d_04_dashboard_active_summary.png) — Populated dashboard summary (`Total patients: 3`, `Active sessions: 2`, `Needs review: 1`, `Data issues: 0`, live `Patients at a glance` `reviewStatus` badges).
- [stage4d_05_mobile_responsive.png](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/evidence/stage4d_05_mobile_responsive.png) — `390x844` mobile layout with zero horizontal page overflow.

---

## 4. Protected Assets, Cleanup & Git Scope
- **Frozen Model C SHA-256 Hashes (Unchanged):**
  - `feature_schema.json`: `C4D1B2E26A15569CEF6328D8E4D06F5DC5677F0D8B74F709F30C104715D472F7`
  - `inference.py`: `EEAE1F80AFF0CC477398CD7B1A3D9BE3C7E5D2B4C8EE775E1EDD284BD87928BB`
  - `model_c_pipeline.joblib`: `FAEF00B08C6406BB0277C6CA05F15239ACFC8365561D9C9A07EBA3554939A6CA`
  - `model_manifest.json`: `14D63230374DF51179BFD7694B70D4C9B19226311D7D57D7A2D006D9F2481418`
  - `model_metadata.json`: `02304CC6C5FA958D818BF899AB067C74D01C7D1A97580C63BAF2AB669CF5A714`
- **Migrations, Prototype & Security:** Flyway `V1`–`V6`, `ai-service/`, `simulator-worker/`, Spring Security / CSRF / RBAC filters, and original `SomniSense` prototype remain untouched.
- **Cleanup Verification:** `%TEMP%\somnisense_*` is empty (`0` temporary clusters, logs, or password files left behind), and only pre-existing port `5432` (`PID 13740`) remains listening.
- **Remaining Limitations:** Clinical and hardware-device validation remain outside software simulation scope; temporary-cluster verification ran against PostgreSQL 19 on Windows with Apache `httpd.exe` occupying port `8080`, so E2E targeted backend port `8081`.
