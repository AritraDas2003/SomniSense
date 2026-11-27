# SOMNISENSE — Stage 4 Milestone 4D Pre-Implementation Audit
## Dashboard Summary API & React Live Prediction UI

**Branch:** `stage4/continuous-monitoring-simulator` (HEAD `422a0ba856fe5d9ffc399a757486b304744ba06e`)  
**Specification Baselines:**
- [STAGE4_PRE_IMPLEMENTATION_AUDIT.md](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/STAGE4_PRE_IMPLEMENTATION_AUDIT.md) (v2.3)
- [STAGE4_MILESTONE_4A_REPORT.md](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/STAGE4_MILESTONE_4A_REPORT.md)
- [STAGE4_MILESTONE_4B_REPORT.md](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/STAGE4_MILESTONE_4B_REPORT.md)
- [STAGE4_MILESTONE_4C_REPORT.md](file:///C:/Users/LORD/Downloads/Minor_Project/SomniSense_Team/docs/integration/STAGE4_MILESTONE_4C_REPORT.md)

**Status:** Pre-Implementation Audit **ONLY**. No application code, React components, Flyway migrations, or Git state have been modified.

---

## 1. Existing Architecture Findings

### 1.1 Backend State (Observed in `backend/src/main/java`)
1. **Monitoring Sessions & Readings (Stages 8–9):**
   - `MonitoringSession` supports `ACTIVE`, `ENDED`, `CANCELLED` via `/api/v1/sessions` (`@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")`).
   - `SensorReadingRepository` exposes paginated readings (`GET /api/v1/sessions/{id}/readings`), latest snapshot (`GET /api/v1/sessions/{id}/readings/latest`), and `findLatestPerSession(...)` (`DISTINCT ON (session_id)`).
2. **Predictions & Retry Infrastructure (Stages 2–4B):**
   - `PredictionController` exposes `GET /api/v1/sessions/{sessionId}/predictions` (`PageResponse<PredictionResponse>`, sorted `windowEnd DESC, processedAt DESC, id ASC`, bounded `size` `1..100`) and `GET /api/v1/sessions/{sessionId}/predictions/latest` (`LatestPredictionResponse` with `sessionId`, `sessionStatus`, `hasPrediction`, `latest`). Both require `ADMIN` or `OPERATOR`.
   - `PredictionResponse` includes `id`, `sessionId`, `modelVersion`, `windowStart`, `windowEnd`, `predictedClass` (`NORMAL | APNEA_SUSPECTED | null`), `confidenceScore` (`[0,1]` or `null`), `predictionStatus` (`COMPLETED | NEEDS_REVIEW | REJECTED | ERROR`), `inputQualityStatus` (`VALID | DEGRADED | REJECTED | null`), `processedAt`, `processingTimeMs`, `errorMessage`.
   - `SlidingWindowScheduler` and `InferenceWindowJobRepository` run asynchronously against `MonitoringSession` + `ai-service`, storing outcomes cleanly in PostgreSQL without any public inference trigger endpoint.
3. **Simulation Lifecycle (Stages 4A & 4C):**
   - `SimulationController` exposes `POST /api/v1/sessions/{sessionId}/simulation/{start,pause,resume,stop}` and `GET /api/v1/sessions/{sessionId}/simulation` (`SimulationResponse` with `id`, `sessionId`, `scenarioId`, `seed`, `cadenceHz`, `playbackSpeed`, `qualityPreset`, `status`, `lastStep`, `workerGeneration`, `leaseExpiresAt`, `workerInstanceId`, `startedAt`, `updatedAt`).
4. **Device Connectivity & Missing Summary Endpoint:**
   - `ConnectivityPolicy` computes `CONNECTED` (`<= 30s`), `STALE` (`<= 120s`), `DISCONNECTED` (`> 120s`), and `UNKNOWN` (`lastSeenAt == null`) dynamically from `devices.last_seen_at` at request time.
   - `PatientSummaryResponse` (`GET /api/v1/patients`) currently returns `currentDevice`, `activeSession`, and `latestReading`, with no `reviewStatus` field.
   - **Gap:** No `DashboardController` or `GET /api/v1/dashboard/summary` endpoint currently exists.

### 1.2 Frontend State (Observed in `frontend/src`)
1. **Stack & Routing (`frontend/package.json`, `frontend/src/app/router.jsx`):**
   - React `19.3.1`, Vite `8.3.6`, Redux Toolkit, React Router `7.18.6`, Axios `1.21.1`, Recharts `3.11.0`, Lucide Icons.
   - Protected routes: `/dashboard`, `/patients`, `/patients/new`, `/patients/:patientId`, `/devices`. Empty placeholder files (`alerts/AlertsPage.jsx`, `analytics/AnalyticsPage.jsx`, `monitoring/LiveMonitoringPage.jsx`, `sessions/SessionsPage.jsx`) are unrouted.
   - `package.json` defines `dev`, `build`, `lint`, and `preview`; **no Vitest / React Testing Library runner is currently configured** in `frontend/package.json`. Playwright (`1.65.2`) is installed in the Python environment for Chromium E2E.
2. **HTTP & Security Integration (`httpClient.js`, `SessionExpiryListener.jsx`):**
   - Axios targets `VITE_API_BASE_URL` (default `http://localhost:8080/api/v1`) with `withCredentials: true`, `withXSRFToken: true`, `XSRF-TOKEN` -> `X-XSRF-TOKEN`.
   - Automatic single-retry CSRF recovery on `403 CSRF_TOKEN_INVALID` and global `somnisense:session-expired` event on `401` (except startup `GET /auth/me` unless `SESSION_INVALID`).
3. **Current Dashboard Gaps (`dashboardService.js`, `DashboardPage.jsx`):**
   - `getDashboard()` currently performs 4 separate API calls (`getPatients`, `/sessions?status=ACTIVE`, `/devices?connectivity=STALE`, `/devices?connectivity=DISCONNECTED`) and hardcodes `itemsNeedingReview: null`.
   - `DashboardPage.jsx` renders `"Partial overview metrics ... Review-flag counts remain unavailable"` and `PatientsTable` displays `"Unavailable"` in the `Review` column because live `PatientSummaryResponse` lacks `reviewStatus`.
4. **Current Patient Detail Gaps (`patientService.js`, `PatientDetailsPage.jsx`):**
   - `getPatientOverview(patientId)` hardcodes `predictions: []` and `mlAvailable: false`, causing `"Model service unavailable — Model prediction endpoints are not active in Stage 1"` to always display and suppressing prediction UI cards.
   - `PatientDetailsPage.jsx` fetches once on mount with **no `10s` polling**, no prediction history list, and no session/simulator controls.
   - `StatusBadge.jsx` maps `NEEDS_REVIEW`, `NONE`, `ACTIVE`, `ENDED`, `CANCELLED`, `CONNECTED`, `STALE`, `DISCONNECTED`, `UNKNOWN`, but **does not map** `COMPLETED`, `REJECTED`, `ERROR`, `VALID`, `DEGRADED`, or simulator statuses (`RUNNING`, `PAUSED`, `STOPPED`, `FAILED`).

---

## 2. Proposed Backend Contract: `GET /api/v1/dashboard/summary`

### 2.1 Security & Side-Effect Guarantees
- **Access Control:** `@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")`; unauthenticated requests return `401` (`UNAUTHORIZED` / `SESSION_INVALID`); unauthorized roles return `403 FORBIDDEN`.
- **Read-Only Guarantee:** `@Transactional(readOnly = true)`. Never calls `ai-service`, never calls `simulator-worker`, never triggers inference, and never mutates `monitoring_sessions`, `predictions`, `inference_window_jobs`, or `simulation_runs`.
- **Timestamps:** All instants serialized in ISO-8601 UTC (`...Z`), using server clock `Instant.now()` once per request (`generatedAt`) for consistent `ConnectivityPolicy` evaluation.

### 2.2 JSON Response Schema (`DashboardSummaryResponse`)

Distinguishes **persisted** database records from **derived** request-time metrics:

```json
{
  "generatedAt": "2026-11-27T06:00:00.123Z",
  "modelVersion": "phase7_model_c:1.0.0",
  "summary": {
    "patientCount": 12,
    "activeSessions": 2,
    "itemsNeedingReview": 1,
    "dataIssues": 1,
    "activeSessionStatuses": {
      "completed": 14,
      "needsReview": 3,
      "rejected": 1,
      "error": 0,
      "degradedWindows": 1,
      "pendingRetryJobs": 0
    }
  },
  "activeSessionsList": [
    {
      "sessionId": "6b2c1a90-...",
      "sessionCode": "SES-20261127-A1",
      "patientId": "3e8d7c60-...",
      "patientCode": "PT-SIM001",
      "patientDisplayName": "Demo Patient A",
      "sourceType": "SIMULATED",
      "deviceId": "9a1f2b30-...",
      "deviceCode": "DEV-SIM-01",
      "connectivityStatus": "CONNECTED",
      "deviceLastSeenAt": "2026-11-27T05:59:59Z",
      "startedAt": "2026-11-27T05:54:00Z",
      "simulation": {
        "id": "11112222-...",
        "scenarioId": "severe_osa",
        "status": "RUNNING",
        "lastStep": 360,
        "workerGeneration": 1
      },
      "latestReading": {
        "spo2": 88.40,
        "heartRate": 76.20,
        "measuredAt": "2026-11-27T05:59:59Z"
      },
      "latestPrediction": {
        "id": "44445555-...",
        "sessionId": "6b2c1a90-...",
        "modelVersion": "phase7_model_c:1.0.0",
        "windowStart": "2026-11-27T05:58:50Z",
        "windowEnd": "2026-11-27T05:59:50Z",
        "predictedClass": "APNEA_SUSPECTED",
        "confidenceScore": 0.874200,
        "predictionStatus": "NEEDS_REVIEW",
        "inputQualityStatus": "VALID",
        "processedAt": "2026-11-27T05:59:53Z",
        "processingTimeMs": 14,
        "errorMessage": null
      }
    }
  ],
  "recentPatients": [
    {
      "id": "3e8d7c60-...",
      "patientCode": "PT-SIM001",
      "fullName": "Demo Patient A",
      "alias": "Demo Patient A",
      "sourceType": "SIMULATED",
      "status": "ACTIVE",
      "currentDevice": {
        "assignmentId": "...",
        "deviceId": "...",
        "deviceCode": "DEV-SIM-01",
        "connectivityStatus": "CONNECTED",
        "lastSeenAt": "2026-11-27T05:59:59Z",
        "assignedAt": "2026-11-27T05:50:00Z"
      },
      "activeSession": {
        "sessionId": "6b2c1a90-...",
        "sessionCode": "SES-20261127-A1",
        "startedAt": "2026-11-27T05:54:00Z"
      },
      "latestReading": {
        "spo2": 88.40,
        "heartRate": 76.20,
        "measuredAt": "2026-11-27T05:59:59Z"
      },
      "reviewStatus": "NEEDS_REVIEW",
      "createdAt": "2026-11-27T05:45:00Z"
    }
  ]
}
```

### 2.3 Metric Definitions & Persisted vs. Derived Classification

| Field | Kind | Exact Computation / Source |
|---|---|---|
| `generatedAt` | Derived | Server UTC `Instant.now()` when summary transaction starts |
| `summary.patientCount` | Persisted | `SELECT COUNT(*) FROM patients` (matches existing Stage 1 total) |
| `summary.activeSessions` | Persisted | `SELECT COUNT(*) FROM monitoring_sessions WHERE status = 'ACTIVE'` |
| `summary.itemsNeedingReview` | Persisted/Derived | Count of currently active (or latest per session; see Section 6 Decision D1) items with `prediction_status = 'NEEDS_REVIEW'` (never `CANCELLED` sessions) |
| `summary.dataIssues` | Derived | `COUNT(*)` of `devices` where `is_active = true` and `last_seen_at IS NOT NULL` and `last_seen_at < generatedAt - staleAfter` (evaluates both `STALE` and `DISCONNECTED` via `ConnectivityPolicy` threshold; excludes `UNKNOWN`) |
| `summary.activeSessionStatuses.*` | Persisted | `GROUP BY prediction_status, input_quality_status` across `ACTIVE` sessions (plus `RETRY_WAIT` count in `inference_window_jobs` for active sessions) |
| `activeSessionsList[].connectivityStatus` | Derived | `ConnectivityPolicy.evaluate(device.lastSeenAt, generatedAt)` |
| `activeSessionsList[].simulation` | Persisted | Batch-loaded from `simulation_runs` for active sessions (`null` if physical or unrun) |
| `activeSessionsList[].latestReading` | Persisted | Single `DISTINCT ON (session_id)` query via `SensorReadingRepository.findLatestPerSession` |
| `activeSessionsList[].latestPrediction` | Persisted | Single `DISTINCT ON (session_id)` query on `predictions` ordered by `(session_id, window_end DESC, processed_at DESC, id DESC)` |
| `recentPatients[]` | Mixed | Top 4 newest patients (`createdAt DESC, id ASC`) with batch-joined `currentDevice`, `activeSession`, `latestReading`, and latest `reviewStatus` (avoiding PII fields like DOB, contact, or medical notes; exact `PatientSummaryResponse` field discipline) |

### 2.4 Database Query Efficiency Strategy
All aggregation runs inside **one `@Transactional(readOnly = true)` method** with fixed **O(1) bounded queries** (never N+1 loops over sessions or patients):
1. `patientRepository.count()` + `patientRepository.findAll(PageRequest.of(0, 4, DESC createdAt))`.
2. `deviceRepository.countActiveWithStaleOrDisconnected(staleCutoff)` (`lastSeenAt < generatedAt.minus(staleAfter)`).
3. `sessionRepository.findTopActiveOrdered(PageRequest.of(0, 50))` + `sessionRepository.countByStatus(ACTIVE)`.
4. Batch `assignmentRepository`, `deviceRepository`, `patientRepository`, `simulationRunRepository` (`findBySessionIdIn`).
5. Batch `readingRepository.findLatestPerSession(activeSessionIds)`.
6. Batch `predictionRepository.findLatestPerSession(distinctSessionIds)` (`DISTINCT ON (session_id)`) + single status-count aggregate query.
7. `inferenceWindowJobRepository.countBySessionIdsAndStatus(activeSessionIds, RETRY_WAIT)`.

Total SQL round-trips: **<= 10 fixed queries** even on empty DB (`0` rows short-circuits batch lookups) or hundreds of sessions.

### 2.5 Empty-State & Error Behavior
- **Empty Database:** HTTP `200 OK` with `patientCount: 0`, `activeSessions: 0`, `itemsNeedingReview: 0`, `dataIssues: 0`, `activeSessionsList: []`, `recentPatients: []`. Zero `null` metric placeholders on summary cards once backend endpoint is live.
- **Data Freshness Semantics:** `generatedAt` records exact snapshot time; per-device `connectivityStatus` reflects `STALE` (>30s since reading) and `DISCONNECTED` (>120s); `latestPrediction` retains `windowStart`, `windowEnd`, `processedAt`, and `inputQualityStatus` so the UI never displays old predictions as current without timestamps.
- **Error Responses:** Standard `ApiError` JSON shape (`401 UNAUTHORIZED`, `403 FORBIDDEN`, `500 INTERNAL_ERROR`).

---

## 3. React Integration Plan

### 3.1 API Service Layer (`frontend/src/services/`)
1. **`dashboardService.js`:**
   - Call `httpClient.get('/dashboard/summary')` in `LIVE` mode (preserving `mockMonitoringService.getDashboard()` cleanly when `useMockApi` is true).
   - Map `summary`, `activeSessionsList`, `recentPatients` (falling back safely if empty), `generatedAt`, and `dataMode: 'LIVE'`.
2. **`patientService.js`:**
   - Replace hardcoded `predictions: []`, `mlAvailable: false` in `getPatientOverview(patientId)` with parallel requests to:
     - `GET /sessions/{sessionId}`
     - `GET /sessions/{sessionId}/readings/latest`
     - `GET /sessions/{sessionId}/readings?page=0&size=300` (and last page if `totalPages > 1`)
     - `GET /sessions/{sessionId}/predictions/latest`
     - `GET /sessions/{sessionId}/predictions?page=0&size=20`
     - `GET /sessions/{sessionId}/simulation` (gracefully catching `404` -> `simulation: null` for physical or unrun sessions).
   - Add simulator control helpers (`startSimulation`, `pauseSimulation`, `resumeSimulation`, `stopSimulation`) and session lifecycle helpers (`startSession`, `endSession`, `cancelSession`) routing exclusively through `httpClient` (`X-XSRF-TOKEN` auto-handled).
   - Update `PatientsPage.jsx` / `PatientSummaryResponse` handling so the `Review` column uses real latest prediction status when present (`NONE` when session has no flags).

### 3.2 Component Updates & States (`frontend/src/`)
1. **`StatusBadge.jsx`:**
   - Add complete Stage 2–4 badge definitions without removing existing keys:
     - Prediction statuses: `COMPLETED` (`Completed`, `success`), `NEEDS_REVIEW` (`Needs review`, `warning`), `REJECTED` (`Rejected`, `muted`), `ERROR` (`Evaluation error`, `danger`).
     - Quality statuses: `VALID` (`Valid signal`, `success`), `DEGRADED` (`Degraded signal`, `warning`).
     - Simulator run statuses: `RUNNING` (`Streaming`, `success`), `PAUSED` (`Paused`, `warning`), `STOPPED` (`Stopped`, `muted`), `FAILED` (`Worker error`, `danger`).
2. **`DashboardPage.jsx`:**
   - Remove the stale Stage 1 `"Partial overview metrics — Review-flag counts remain unavailable"` info banner in `LIVE` mode.
   - Render all 4 summary KPI cards (`patientCount`, `activeSessions`, `itemsNeedingReview`, `dataIssues`) directly from `/api/v1/dashboard/summary` with explicit `0` support (never treating `0` as unavailable!).
   - Add an **Active Monitoring Sessions & Live Flags** section displaying each active session's patient, device connectivity badge, latest SpO₂/HR reading, simulator run status, and latest Model C prediction status + timestamp (`windowEnd`), linking straight to `/patients/:patientId`.
   - Implement clean `10s` background auto-refresh when `activeSessions > 0` and page is visible (`visibilitychange`), plus manual Refresh control, loading skeletons on initial load only (avoiding jarring spinner flashes on background refresh), error alert + Retry button, and stale-data warning indicator if background refresh fails while keeping last known snapshot visible.
3. **`PatientDetailsPage.jsx`:**
   - Remove Stage 1 `"Model service unavailable"` notice once `mlAvailable: true`.
   - **Live Polling Lifecycle:** Poll `getPatientOverview(patientId)` every **`10s` ONLY while `session?.status === 'ACTIVE'`**; automatically stop interval when session transitions to `ENDED` or `CANCELLED`, on unmount, or on session expiry (`401`).
   - **Session & Simulator Control Strip (when session/device eligible):** Show Start/End session controls and, for `SIMULATED` devices on an `ACTIVE` session, scenario/seed selector + `Start`, `Pause`, `Resume`, `Stop` simulator controls with inline pending state & safe error banners.
   - **Latest Prediction & Window History Panel:**
     - If `ACTIVE` session and `!latestPrediction`: display neutral `"Evaluating initial 60s baseline window..."` empty state (never fabricate `NORMAL`).
     - If `COMPLETED` or `NEEDS_REVIEW`: render class (`NORMAL` / `APNEA_SUSPECTED`), confidence %, `inputQualityStatus`, window range `[windowStart, windowEnd)`, `modelVersion`, and mandatory clinical boundary banner: **"Model output only. This is not a diagnosis."**
     - If `REJECTED`: distinguish **`DEGRADED`** (`"Signal quality degraded — inference skipped"`) vs **`REJECTED`** (`"Insufficient / invalid window — inference skipped"`), showing `null` prediction values safely.
     - If `ERROR`: render non-alarmist `"Evaluation failed after retries"` banner showing safe `errorMessage` and `ERROR` badge.
     - Render recent **Window Prediction History** table (most recent 20 windows) cleanly responsive on desktop and mobile viewports.

---

## 4. Security & Privacy Verification Plan
- Frontend builds strictly against Spring Boot (`VITE_API_BASE_URL`); zero references to `:8090` (`ai-service`), `:8095` (`simulator-worker`), `X-Internal-AI-Key`, `X-Internal-Sim-Key`, or `X-Ingestion-Key` in browser bundles.
- `DashboardSummaryResponse` contains no sensitive patient PII beyond `patientCode` and `patientDisplayName` (no DOB, contact details, or medical history text), matching `PatientSummaryResponse`.
- Every mutation button (`POST /sessions`, `POST /simulation/start|pause|resume|stop`, `POST /sessions/.../end`) relies on Spring Security `CookieCsrfTokenRepository` + `JSESSIONID`.

---

## 5. Acceptance Test Matrix (To Execute During Milestone 4D Implementation)

| Layer | Case | Expected Verified Behaviour |
|---|---|---|
| Backend (`somnisense_test_db`) | `DashboardSummaryIntegrationTest`: unauthenticated (`401`), non-staff role (`403`), `ADMIN`/`OPERATOR` (`200`) | RBAC strictly enforced |
| Backend | Empty DB state | All KPIs `0`, empty lists, HTTP `200`, zero errors |
| Backend | Multi-patient isolation & aggregation | Independent sessions across 3+ patients, `ACTIVE` + `ENDED` + `CANCELLED` scoping, `STALE`/`DISCONNECTED` `ConnectivityPolicy` counts, `DEGRADED`/`REJECTED`/`ERROR` counts match DB |
| Backend | Zero inference side effects & query budget | No AI HTTP call on `GET /dashboard/summary`; Hibernate statement/query verification confirms bounded O(1) query count |
| Backend | Full Stage 1–4 regression | All existing `198+` Maven integration tests + new 4D tests pass (`0` skipped/failed) |
| Frontend (`vite build` + `eslint`) | Mock & Live production builds + lint | `npm run build` (both `VITE_USE_MOCK_API=false` and default) + `npm run lint` exit `0`; bundle grep confirms zero Python internal URLs/keys |
| Browser E2E (Playwright Chromium + live stack) | Initial dashboard load, empty/loaded states, session creation, multi-patient `1.0x` simulation (`normal_sleep` + `severe_osa`) | Charts populate with 1 Hz readings; after `~65s–90s` live `60s` windows evaluate cleanly; `DashboardPage` & `PatientDetailsPage` update live via `10s` polling without console/page/5xx errors |
| Browser E2E | Pause -> Resume -> End Session -> Cancel/Stale checks | Simulator pauses & resumes from checkpoint without gaps; polling halts on `ENDED`; `"Model output only. This is not a diagnosis."` visible; `401` redirects cleanly to `/login`; mobile/desktop responsive layout clean |

---

## 6. Unresolved Decisions Requiring Approval Before Milestone 4D Coding

1. **Decision D1 — Exact Scope of `summary.itemsNeedingReview`:**
   - **Option A (Recommended):** Count distinct **`ACTIVE` (or non-`CANCELLED` active/recent)** sessions whose **latest** prediction has `predictionStatus = 'NEEDS_REVIEW'` (so a single active 5-minute apnea patient counts as `1` patient needing review rather than inflating to `30` overlapping 10s-stride windows). In addition, `summary.activeSessionStatuses.needsReview` provides the window-level count for granularity.
   - **Option B:** Count total persisted `predictions` rows across active (or non-cancelled) sessions with `predictionStatus = 'NEEDS_REVIEW'` (matching `mockMonitoringService.js`).
   - *Approval needed on which definition drives the top KPI card.*

2. **Decision D2 — `summary.patientCount` Scope (`ALL` vs `ACTIVE` Patients):**
   - Existing Stage 1 `DashboardPage` shows `"Total patients — System-wide total"` (`patientRepository.count()`, including `ACTIVE` and `INACTIVE`).
   - *Recommendation:* Keep `patientCount` as system-wide total (`patientRepository.count()`) for backward continuity with Stage 1 cards.

3. **Decision D3 — `PatientsPage` (`/patients`) Review Column Source:**
   - Currently `GET /api/v1/patients` returns `PatientSummaryResponse` without `reviewStatus`, so `/patients` shows `"Unavailable"` even when predictions exist.
   - *Recommendation:* Add an optional `reviewStatus` field (latest prediction status of the patient's active session, or `"NONE"`, derived via a single batch `DISTINCT ON (session_id)` query matching existing zero-N+1 patterns) to `PatientSummaryResponse` so both `DashboardPage` and `PatientsPage` display accurate review badges without extra frontend requests. (Alternatively, leave `PatientSummaryResponse` unchanged and only populate `reviewStatus` on `DashboardSummaryResponse.recentPatients`.)

4. **Decision D4 — Simulator Controls Placement in React UI:**
   - Adding `Start / Pause / Resume / Stop` simulator controls + `Start / End Session` directly on `PatientDetailsPage.jsx` for `SIMULATED` patients makes live E2E and demo operation self-contained within existing routes without adding unrequested routes.
   - *Confirm whether `PatientDetailsPage.jsx` should expose session/simulator control buttons cleanly to `ADMIN`/`OPERATOR` staff.*

5. **Decision D5 — Frontend Automated Test Tooling:**
   - `frontend/package.json` currently has Vite + ESLint (`no Vitest` installed). Installing devDependencies with `pnpm`/`npm` vs keeping `package.json` minimal and verifying React UI via `eslint` + `vite build` + full-stack Chromium Playwright E2E tests (`tests/e2e/`) avoids modifying JS dependency lockfiles unless unit-level component tests (Vitest + Testing Library) are desired alongside Playwright.
   - *Confirm whether to add Vitest + `@testing-library/react` to `frontend/package.json` OR verify React UI exclusively via `eslint` + `vite build` + live Playwright E2E suite.*

---

## 7. Proposed Milestone 4D Implementation Sequence (Upon Approval)

1. Implement `com.somnisense.backend.dashboard` (`DashboardSummaryResponse`, `DashboardService`, `DashboardController`), batch query methods in `PredictionRepository` / `DeviceRepository` / `InferenceWindowJobRepository`, and (if D3 approved) `PatientSummaryResponse` `reviewStatus`.
2. Add `DashboardSummaryIntegrationTest` (`somnisense_test_db`) verifying RBAC, empty state, multi-patient isolation, connectivity derivation, status counts, zero AI side effects, and query count; run complete Maven QA suite.
3. Update `frontend/src/services/{dashboardService,patientService}.js`, `StatusBadge.jsx`, `DashboardPage.jsx`, and `PatientDetailsPage.jsx`.
4. Verify `npm run lint` and `npm run build` (both mock and live modes), run full-stack Chromium Playwright E2E multi-patient validation (`PostgreSQL` + `Spring Boot` + `ai-service` + `simulator-worker` + `Vite` frontend), finalize documentation (`STAGE4_MILESTONE_4D_REPORT.md` & overall `STAGE4_IMPLEMENTATION_REPORT.md`), commit, and push to both remotes.
