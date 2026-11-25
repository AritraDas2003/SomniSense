# SOMNISENSE — Stage 1: Frontend ↔ Spring Boot Authentication & Core API Integration Report

> **Authoritative Repository:** `https://github.com/AritraDas2003/SomniSense.git`  
> **Base Audited Commit:** `main` @ `47ee5a725a263e3c7266aeabd1e3e198a18742b6`  
> **Working Branch:** `stage1/frontend-spring-boot-integration` (uncommitted, isolated working tree)  
> **Date:** 2026-11-25  

---

## 1. Files Modified

All changes were confined strictly to `frontend/src/` and this report (`docs/integration/STAGE1_FRONTEND_BACKEND_INTEGRATION_REPORT.md`). Zero files were modified in `backend/`, database migrations (`V1`–`V4`), `package.json`, `package-lock.json`, or any frozen Model C / Phase 9 simulator assets.

| File Path | Summary of Modification |
|---|---|
| `frontend/src/services/api/apiConfig.js` | Safe `import.meta.env?.VITE_*` access preserving `useMockApi` default and `apiBaseUrl`. |
| `frontend/src/services/api/httpClient.js` | Enabled `withCredentials`, `withXSRFToken`, `XSRF-TOKEN` / `X-XSRF-TOKEN`; added `ApiError` normalization (`status`, `code`, `apiMessage`, `path`, `correlationId`, `fieldErrors`), single automatic `CSRF_TOKEN_INVALID` retry, and scoped `401` / `SESSION_INVALID` expiry events. |
| `frontend/src/services/authService.js` | Implemented `ensureCsrfToken()`, `login({ login, password })`, `getCurrentUser()` (`GET /auth/me`), and `logout()` (`POST /auth/logout`), mapping `CurrentUserResponse` cleanly. |
| `frontend/src/services/mocks/mockAuthService.js` | Updated mock auth to match Spring Boot's `{ login, password }` contract supporting username or email login and `INVALID_CREDENTIALS` shape. |
| `frontend/src/features/auth/authSlice.js` | Added `restoreSession` and `signOutUser` async thunks, `initialized` bootstrap state, and `sessionInvalidReason` tracking without storing passwords, session IDs, or CSRF tokens. |
| `frontend/src/hooks/useAuth.js` | Updated `signOut()` to dispatch `signOutUser` (`POST /api/v1/auth/logout`). |
| `frontend/src/app/SessionExpiryListener.jsx` | Forwarded `{ code, message }` event detail into Redux `sessionExpired` and cleared mock storage only when `useMockApi` is enabled. |
| `frontend/src/App.jsx` | Added `AuthBootstrap` dispatching `restoreSession()` on mount to restore cookie-backed sessions after browser refresh. |
| `frontend/src/app/router.jsx` | Guarded `/login` and protected routes with `!initialized` loading state so browser refresh does not prematurely redirect before `GET /auth/me` resolves. |
| `frontend/src/pages/auth/LoginPage.jsx` | Replaced email-only `username` input with `login` field accepting both username and email; added `SESSION_INVALID` messaging and conditional mock hints. |
| `frontend/src/components/layout/AppLayout.jsx` | Awaited async `signOut()` on logout and added safe `user?.name \|\| user?.username` display/initials rendering. |
| `frontend/src/services/patientService.js` | Updated `getPatients()` for `PageResponse` + backend query params, `createPatient()` for `CreatePatientRequest`, and `getPatientOverview()` to compose `/patients/{id}`, `/sessions`, `/readings/latest`, and `/readings` (removing nonexistent `/patients/{id}/monitoring`). |
| `frontend/src/services/deviceService.js` | Updated `getDevices()` for `PageResponse` + backend query filters and `assignDevice({ deviceId, patientId, replaceExisting })` for `POST /device-assignments`. |
| `frontend/src/services/dashboardService.js` | Removed nonexistent `GET /dashboard/summary`; composed system-wide counts from `PageResponse.totalElements` via `/patients`, `/sessions?status=ACTIVE`, and `/devices?active=true&connectivity=STALE\|DISCONNECTED`, leaving `itemsNeedingReview: null` until backend AI/alerts endpoints exist. |
| `frontend/src/services/mocks/mockMonitoringService.js` | Aligned mock responses to Spring Boot `PageResponse` (`content`, `page`, `size`, `totalElements`, `totalPages`), `currentDevice`, `activeSession`, `latestReading`, and `LatestReadingsResponse` structure. |
| `frontend/src/services/mocks/mockPatientService.js` | Preserved paged patient mock delegation (`listPatients(options)`). |
| `frontend/src/services/mocks/mockDeviceService.js` | Aligned mock device list to `PageResponse<DeviceResponse>` (`active`, `currentAssignment`) and enforced `409 DEVICE_ALREADY_ASSIGNED` / `PATIENT_ALREADY_HAS_DEVICE` unless `replaceExisting: true`. |
| `frontend/src/components/common/StatusBadge.jsx` | Added mappings for all backend enums (`ACTIVE`, `INACTIVE`, `CONNECTED`, `STALE`, `DISCONNECTED`, `UNKNOWN`, `ENROLLED`, `PENDING`, `SETUP_REQUIRED`, `ENDED`, `CANCELLED`). |
| `frontend/src/components/common/PatientsTable.jsx` | Replaced fabricated `device`/`session`/`freshness` fields with backend `currentDevice`, `activeSession`, `latestReading`, connectivity status badge, and server-side `PageResponse` pagination + status/source/search filters. |
| `frontend/src/pages/patients/PatientsPage.jsx` | Wired server-side `page`, `size`, `q`, `sourceType`, `status` into `getPatients()` and `PageResponse` rendering. |
| `frontend/src/pages/patients/AddPatientPage.jsx` | Mapped backend `VALIDATION_FAILED` `fieldErrors` into form fields (`fullName`, `alias`, `sourceType`) alongside `root.server`. |
| `frontend/src/pages/patients/PatientDetailsPage.jsx` | Wired real `PatientDetailResponse`, `SessionResponse`, `LatestReadingsResponse` (`latest`, `lastSpo2`, `lastHeartRate`, `connectivityStatus`), and `ReadingResponse[]` charts (`connectNulls={false}`); keeps ML banner `mlAvailable: false` in live mode. |
| `frontend/src/pages/devices/DevicesPage.jsx` | Aligned to `DeviceResponse` (`active`, `currentAssignment`, `enrollmentStatus`, `connectivityStatus`), added `PageResponse` pagination, and implemented explicit inline confirmation (`replaceExisting: true`) for `409` assignment conflicts. |
| `frontend/src/pages/dashboard/DashboardPage.jsx` | Displayed `'—'` for unavailable `itemsNeedingReview` in live mode with explicit notice; avoided calculating system-wide counts from page 0 alone. |

*(Unmodified empty placeholder pages `AlertsPage.jsx`, `AnalyticsPage.jsx`, `LiveMonitoringPage.jsx`, and `SessionsPage.jsx` remain untouched per Stage 1 exclusions.)*

---

## 2. Changes Implemented

1. **Spring Security Cookie + CSRF Transport (`httpClient.js`):** Configured Axios with `withCredentials: true`, `withXSRFToken: true`, `xsrfCookieName: 'XSRF-TOKEN'`, and `xsrfHeaderName: 'X-XSRF-TOKEN'`.
2. **End-to-End Authentication & Refresh Restoration (`authService.js`, `authSlice.js`, `App.jsx`, `router.jsx`, `LoginPage.jsx`):** Added `GET /auth/csrf` priming before `POST /auth/login` (`{ login, password }` supporting username or email), `GET /auth/me` session hydration on app boot, `POST /auth/logout` session invalidation on sign-out, and differentiated `SESSION_INVALID` handling.
3. **Patients, Sessions & Readings Composition (`patientService.js`, `PatientsPage.jsx`, `PatientsTable.jsx`, `PatientDetailsPage.jsx`, `AddPatientPage.jsx`):** Updated UI & services to use exact Spring Boot DTO properties (`currentDevice`, `activeSession`, `latestReading`, `lastSpo2`, `lastHeartRate`, `connectivityStatus`) and zero-based `PageResponse`.
4. **Device Inventory & Safe Reassignment Confirmation (`deviceService.js`, `DevicesPage.jsx`):** Replaced `isActive` / `assignedPatient` with `active` / `currentAssignment`, added paginated device listing, and enforced explicit user confirmation on `409 DEVICE_ALREADY_ASSIGNED` or `PATIENT_ALREADY_HAS_DEVICE` before retrying with `replaceExisting: true`.
5. **Honest Dashboard Aggregation (`dashboardService.js`, `DashboardPage.jsx`):** Removed nonexistent `/dashboard/summary`, queried server-wide `totalElements` via `PageResponse`, and left `itemsNeedingReview` explicitly unavailable (`null` → `'—'`) without fabricating metrics.
6. **Preserved Mock Mode:** Updated mock adapters (`mockAuthService.js`, `mockMonitoringService.js`, `mockDeviceService.js`, `mockPatientService.js`) to emit the same `PageResponse` and backend DTO shapes so `VITE_USE_MOCK_API=true` continues functioning identically.

---

## 3. Authentication Flow

1. **App Startup / Browser Refresh (`App.jsx` → `AuthBootstrap` → `restoreSession`):**
   - Calls `GET /api/v1/auth/csrf` to ensure `XSRF-TOKEN` cookie exists, then `GET /api/v1/auth/me`.
   - `router.jsx` renders a loading fallback until `initialized === true` so direct links (e.g., `/patients/:id`) do not flash `/login` while session verification is in flight.
   - `200 OK` restores `CurrentUserResponse` into Redux in-memory state (`user.id`, `username`, `email`, `role`, `active`, `lastLoginAt`, `createdAt`). `401 UNAUTHORIZED` clears `user` cleanly without showing an expiry alert; `401 SESSION_INVALID` sets `sessionExpired = true` with `SESSION_INVALID` notice.
2. **Login (`LoginPage.jsx` → `authService.login`):**
   - Sends `GET /api/v1/auth/csrf`, then `POST /api/v1/auth/login` with `{ login, password }`.
   - Spring Boot issues `HttpOnly` `JSESSIONID` cookie and rotates `XSRF-TOKEN`. No passwords, session IDs, or CSRF tokens are stored in Redux, `localStorage`, or `sessionStorage` in live mode.
3. **CSRF Recovery & Session Expiry (`httpClient.js` → `SessionExpiryListener.jsx`):**
   - If a mutating request receives `403 CSRF_TOKEN_INVALID`, `httpClient` calls `GET /api/v1/auth/csrf` once and retries the request.
   - Any non-login `401` (or `/auth/me` `401 SESSION_INVALID`) dispatches `somnisense:session-expired` with `{ code, message }`, clearing Redux auth state and redirecting to `/login`.
4. **Logout (`AppLayout.jsx` → `useAuth.signOut` → `signOutUser`):**
   - Sends `POST /api/v1/auth/logout` (automatically carrying `JSESSIONID` + `X-XSRF-TOKEN`), then clears Redux user state and navigates to `/login`.

---

## 4. API Contract Mappings

| Frontend Action / View | Backend Endpoints Used (`VITE_API_BASE_URL`) | Exact Request / Response Fields Consumed |
|---|---|---|
| **CSRF & Auth** | `GET /auth/csrf`, `POST /auth/login`, `GET /auth/me`, `POST /auth/logout` | Request: `{ login, password }`; Response: `CurrentUserResponse` (`id`, `username`, `email`, `role`, `active`, `lastLoginAt`, `createdAt`) |
| **Patient Directory** | `GET /patients?q=&sourceType=&status=&page=&size=&sort=&direction=` | `PageResponse<PatientSummaryResponse>`: `id`, `patientCode`, `fullName`, `alias`, `sourceType`, `status`, `currentDevice`, `activeSession`, `latestReading`, `createdAt` |
| **Register Patient** | `POST /patients` | Request: `{ fullName, alias, sourceType }`; Response (`201`): `PatientDetailResponse` (including server-generated `patientCode`) |
| **Patient Overview** | `GET /patients/{id}`, `GET /sessions?patientId=&page=0&size=1`, `GET /sessions/{sid}`, `GET /sessions/{sid}/readings/latest`, `GET /sessions/{sid}/readings?page=&size=300` | `PatientDetailResponse`, `SessionResponse`, `LatestReadingsResponse` (`connectivityStatus`, `deviceLastSeenAt`, `latest`, `lastSpo2`, `lastHeartRate`), `PageResponse<ReadingResponse>` (`measuredAt`, `receivedAt`, `spo2`, `heartRate`, `signalQuality`) |
| **Device Inventory** | `GET /devices?page=&size=&active=&enrollmentStatus=&connectivity=` | `PageResponse<DeviceResponse>`: `id`, `deviceCode`, `deviceType`, `modelName`, `serialNumber`, `active`, `enrollmentStatus`, `connectivityStatus`, `lastSeenAt`, `currentAssignment` (`assignmentId`, `patientId`, `patientCode`, `assignedAt`) |
| **Assign / Reassign Device** | `POST /device-assignments` | Request: `{ deviceId, patientId, replaceExisting }`; Response (`200`/`201`): `AssignmentResponse` (`id`, `active`, `deviceId`, `deviceCode`, `deviceType`, `patientId`, `patientCode`, `patientFullName`, `assignedAt`) |
| **Dashboard KPIs** | `GET /patients?page=0&size=4`, `GET /sessions?status=ACTIVE&page=0&size=1`, `GET /devices?active=true&connectivity=STALE&page=0&size=1`, `GET /devices?active=true&connectivity=DISCONNECTED&page=0&size=1` | Reads server-wide `totalElements` on each `PageResponse`; leaves `itemsNeedingReview` as `null` |

---

## 5. Pagination Handling

- Spring Boot returns zero-based pages shaped as `{ content, page, size, totalElements, totalPages }`.
- `PatientsPage.jsx` (`PAGE_SIZE = 10`) and `DevicesPage.jsx` (`INVENTORY_PAGE_SIZE = 20`) store the full `PageResponse` envelope, reset to `page = 0` when search/filter criteria change, and pass `page` / `size` back to `GET /patients` and `GET /devices`.
- `PatientDetailsPage.jsx` requests `GET /sessions/{id}/readings?page=0&size=300`; when `totalPages > 1`, it fetches the final page (`totalPages - 1`) so the trend view displays the latest bounded 300-sample window without assuming unpaged arrays.
- `DevicesPage.jsx` loads assignment dropdown options with `size: 100` (backend max page size per `PATIENTS.md` / `DEVICES.md`) filtered to `status=ACTIVE` patients and `active=true&enrollmentStatus=ENROLLED` devices.

---

## 6. Error Handling

- `buildApiError(error)` in `httpClient.js` preserves the Spring Boot `ApiError` fields (`status`, `code`, `apiMessage`, `path`, `correlationId`, `fieldErrors`) rather than exposing Axios transport codes or raw stack traces.
- `VALIDATION_FAILED` (`400`) appends field-level details (`field: message`) cleanly via `formatApiErrorMessage` and populates `AddPatientPage.jsx` form field errors via `react-hook-form`'s `setError`.
- `CONFLICT` (`409`) assignment codes `DEVICE_ALREADY_ASSIGNED` and `PATIENT_ALREADY_HAS_DEVICE` are intercepted cleanly in `DevicesPage.jsx` to show the server's conflict message in an inline confirmation dialog before allowing `replaceExisting: true`.
- Other `409` / `400` errors (`SESSION_ACTIVE`, `SOURCE_TYPE_MISMATCH`, `DEVICE_NOT_AVAILABLE`, `PATIENT_NOT_ACTIVE`, `INVALID_CREDENTIALS`, etc.) surface the backend's safe `message` directly in alert banners or form error slots.

---

## 7. Build / Test Results

| Check Type | Command Executed | Scope & Observed Result | Status |
|---|---|---|---|
| **Static Lint (Modified Code)** | `npx eslint src/App.jsx src/app src/components/common src/components/layout src/features src/hooks src/pages src/services` | Every file touched or integrated in Stage 1 | **Passed (`exit 0`, 0 errors, 0 warnings)** |
| **Repo Full Lint (`package.json`)** | `npm run lint` | Entire `frontend/` directory | **Exits `1` solely on 2 pre-existing untouched files:** `src/components/ui/button.jsx:56` (`react-refresh/only-export-components`) and `vite.config.js:11` (`no-undef` `__dirname`) |
| **Production Build (Default Mock Mode)** | `npm run build` (`vite build`) | Full Vite 8.3.8 bundle (`2620 modules transformed`) | **Passed (`exit 0`, built in `687ms`)** |
| **Production Build (`VITE_USE_MOCK_API=false`)** | `VITE_USE_MOCK_API=false VITE_API_BASE_URL=http://localhost:8080/api/v1 npm run build` | Full live-API production bundle (`2620 modules transformed`) | **Passed (`exit 0`, built in `664ms`)** |
| **Service & Error Contract Checks** | `node --input-type=module` harness | Verified Axios `withCredentials`, `withXSRFToken`, `XSRF-TOKEN`/`X-XSRF-TOKEN`, `buildApiError` `VALIDATION_FAILED` extraction, mock username+email login, `PageResponse` shape, DTO property names, and `409 PATIENT_ALREADY_HAS_DEVICE` pre-confirmation | **Passed (`exit 0`: `NODE_CONTRACT_CHECKS_PASSED`)** |
| **Frontend Unit/E2E Test Suite** | Checked `frontend/package.json` | No test runner script (`vitest`/`jest`/`playwright`) is configured in `package.json` | **N/A (none configured)** |
| **Live Browser ↔ Spring Boot Integration** | Socket probes (`127.0.0.1:5432`, `127.0.0.1:8080`) | PostgreSQL (`5432`) and Spring Boot (`8080`) are **not running** in this environment (`PG_5432: False`, `BE_8080: False`) | **NOT EXECUTED (blocked by absent DB/backend services; verified statically against Java source & `docs/api/*.md`)** |

---

## 8. Remaining Backend Dependencies

1. **Dashboard Summary Aggregation (`GET /api/v1/dashboard/summary`):** Required to compute `itemsNeedingReview` (`NEEDS_REVIEW` count) and to replace the 4 parallel list-count calls (`patients`, `sessions`, `STALE` devices, `DISCONNECTED` devices) with one consistent server snapshot.
2. **Predictions Endpoints (`GET /api/v1/sessions/{id}/predictions`, `/latest`):** Required to populate model outputs on `PatientDetailsPage` (`mlAvailable: true`) and review banners (`Stage 2–4`).
3. **Alerts / Review Persistence (`V6` + `/alerts` endpoints):** Required for `itemsNeedingReview` and `AlertsPage.jsx` (`Stage 6`).
4. **Session Control UI & Simulator Controls:** Backend `/sessions` start/end/cancel endpoints already exist, while UI controls, simulator endpoints (`Stage 5`), and SSE/WebSocket push (`Stage 7` / backend `Stage 14`) await later authorized stages.

---

## 9. Known Limitations

- **Live end-to-end HTTP verification** against running PostgreSQL 18 + Spring Boot could not be executed in this environment because neither service is running locally.
- **Assignment Dropdown Pagination Limit:** `DevicesPage.jsx` requests `size: 100` (backend maximum) for active patients and enrolled devices selectors; deployments exceeding 100 active patients or 100 enrolled devices will require searchable async selectors in a later UI enhancement.
- **Real-Time Chart Refresh:** `PatientDetailsPage.jsx` fetches readings on load/manual refresh (`Try again`); automatic 5-second polling or SSE push is deferred beyond Stage 1.
- **Unimplemented Placeholder Pages:** `AlertsPage.jsx`, `AnalyticsPage.jsx`, `LiveMonitoringPage.jsx`, and `SessionsPage.jsx` remain empty (`0` bytes) and unrouted per Stage 1 exclusions.

---

## 10. Git Branch and Commit Verification

- **Authoritative Team Repo Clone Path:** `C:\Users\LORD\.gemini\antigravity\brain\8f3e7595-d599-46ca-a2e3-7fe4a6a02125\scratch\SomniSense_Team`
- **Remote Origin:** `https://github.com/AritraDas2003/SomniSense.git`
- **Branch:** `stage1/frontend-spring-boot-integration` (branched cleanly from audited commit `47ee5a725a263e3c7266aeabd1e3e198a18742b6`)
- **Commit Policy:** Changes left uncommitted in working tree; nothing committed or pushed.
- **Unrelated Asset Preservation:** Backend Java (`backend/src/**`), Flyway (`V1`–`V4`), `package.json`/`package-lock.json`, and the separate local Phase 9/Model C workspace (`e40edd2b15822bc06b531b8cbca5c1b291c95a31`) were not modified.
