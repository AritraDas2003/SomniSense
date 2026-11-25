# SOMNISENSE — STAGE 1 & STAGE 1.1 FINAL MILESTONE REPORT
## Frontend ↔ Spring Boot Core Integration & Authenticated Live E2E Verification

> **Authoritative Repository:** `SomniSense_Team` (`https://github.com/AritraDas2003/SomniSense.git`)  
> **Target Branch:** `stage1/frontend-spring-boot-integration`  
> **Base Commit:** `47ee5a725a263e3c7266aeabd1e3e198a18742b6`  
> **Milestone Commit:** `a4e6e0ecd34e0e55791298483cd9af4ce86e0c37`  
> **Commit Message:** `feat(integration): complete Stage 1 frontend and backend integration`  
> **Execution Date:** 2026-11-25  
> **Status:** **FULLY VERIFIED & COMMITTED** (12 Passed, 0 Failed, 0 Warnings)

---

## 1. Executive Summary

This milestone successfully integrated the React frontend with the Spring Boot backend (`http://127.0.0.1:8081/api/v1`) and PostgreSQL database (`127.0.0.1:5432 / somnisense_db`).

The application is fully operational in live mode (`VITE_USE_MOCK_API=false`), with full fallback preservation for development mock mode (`VITE_USE_MOCK_API=true`). All core authenticated flows—including session lifecycle, CSRF protection, patient registration, directory search, patient profile retrieval with database persistence, dashboard metric aggregation, and device inventory inspection—were verified via an automated, process-isolated Playwright Chromium test suite executing against live backend services.

The milestone concludes with an audited, controlled Git commit adhering strictly to SOMNISENSE architectural and security invariants.

---

## 2. Implemented Frontend ↔ Backend Integration

### A. HTTP Security & Session Management (`httpClient.js`, `authService.js`)
- **Axios Configuration:** Configured `withCredentials: true` and automated CSRF handling (`withXSRFToken: true`, tracking `XSRF-TOKEN` cookie and setting `X-XSRF-TOKEN` request header).
- **CSRF Priming:** Implemented `GET /api/v1/auth/csrf` priming on initial application boot and before authentication requests.
- **CSRF Auto-Retry:** Interceptor detects `403` responses containing `CSRF_TOKEN_INVALID`, re-primes the token from `/auth/csrf`, and replays the original request once.
- **Login Contract:** Standardized credentials payload to `{ login, password }`, supporting staff username (`admin1`) or work email interchangeably.
- **Session Restoration & Invalidation:** On boot, the frontend checks `GET /api/v1/auth/me`. If valid, user state is restored to Redux; if invalid/expired, `SESSION_INVALID` triggers an orderly redirect to `/login` with location memory.
- **Error Normalization:** Standardized backend error schemas (`timestamp`, `status`, `error`, `message`, `path`, `fieldErrors`) into typed `ApiError` instances.

### B. Patient Lifecycle Integration (`patientService.js`, `AddPatientPage.jsx`, `PatientsPage.jsx`, `PatientDetailsPage.jsx`)
- **Pagination & Sorting:** Reconciled Spring Boot `Page<PatientResponse>` DTO structure (`content`, `page`, `size`, `totalElements`, `totalPages`).
- **Registration Flow:** Wired `POST /api/v1/patients` with payload validation (`fullName`, optional `alias`, `sourceType`), receiving 201 Created with generated `patientCode` (e.g. `PT-65GYJMHB`).
- **Real-Time Directory Search:** Implemented debounced query filtering against live backend search endpoints.
- **Profile & Telemetry Resilience:** Reconciled patient details with `currentDevice`, `activeSession`, `lastSpo2`, and `lastHeartRate`. Where backend trend telemetry is pending, empty chart states render gracefully without console errors.

### C. Device Inventory & Patient Assignment (`deviceService.js`, `DevicesPage.jsx`)
- **Inventory Pagination:** Mapped Spring Boot `DeviceResponse` fields (`deviceCode`, `deviceType`, `serialNumber`, `enrollmentStatus`, `connectivityStatus`, `active`, `currentAssignment`).
- **Assignment Workflow:** Wired `POST /api/v1/devices/{deviceId}/assignments` accepting `{ patientId, replaceExisting }`.
- **Conflict Handling:** Built reassignment confirmation dialog intercepting `409 Conflict` (`DEVICE_ALREADY_ASSIGNED` / `PATIENT_ALREADY_HAS_DEVICE`) allowing explicit replacement confirmation.

### D. Dashboard Aggregation (`dashboardService.js`, `DashboardPage.jsx`)
- **Overview Metrics:** Wired `GET /api/v1/dashboard`, extracting live totals for `Total patients`, `Active sessions`, and `Data issues`.
- **Contract Resilience for Unimplemented Endpoints:** Where alerts and AI predictions are pending backend implementation, the "Needs review" card displays `—` and detail `Unavailable (AI/alerts API pending)`.

### E. Mock Mode Parity (`services/mocks/*`)
- Fully maintained mock service implementations under `frontend/src/services/mocks/` ensuring full parity for disconnected local UI development when `VITE_USE_MOCK_API=true`.

---

## 3. Authenticated E2E Test Results

The test suite executed via Playwright Chromium against live services (`127.0.0.1:5173`, `127.0.0.1:8081`, `127.0.0.1:5432`):

| Test ID | Scope | Verification Item | Status | Result / Observations | Evidence Screenshot |
|:---:|:---|:---|:---:|:---|:---|
| **A1** | Authentication | Submit login form as `admin1` | **PASS** | Successfully logged in; redirected to `/dashboard` | [stage1_1_auth_01_dashboard.png](evidence/stage1_1_auth_01_dashboard.png) |
| **A2** | Authorization | TopBar user role verification | **PASS** | TopBar profile badge confirms `ADMIN` role (`ADadmin1ADMIN`) | [stage1_1_auth_01_dashboard.png](evidence/stage1_1_auth_01_dashboard.png) |
| **B1** | Session Integrity | Session persistence after page refresh | **PASS** | Active URL remained `/dashboard`; `admin1` session restored | [stage1_1_auth_02_session_persisted.png](evidence/stage1_1_auth_02_session_persisted.png) |
| **B2** | SPA Navigation | Authenticated SPA navigation across primary routes | **PASS** | Traversed `/patients`, `/devices`, and `/dashboard` cleanly | N/A |
| **C1** | Validation | Client validation on empty patient form | **PASS** | Triggered `Enter the patient’s full name (at least 2 characters).` | N/A |
| **C2** | Registration | Create synthetic patient (`POST /api/v1/patients`) | **PASS** | Created `PT-65GYJMHB` (ID: `132974f7-45b8-4e03-ac97-b4ddc68c6f0f`) | [stage1_1_auth_03_patient_created.png](evidence/stage1_1_auth_03_patient_created.png) |
| **C3** | Directory & Search | Patient directory display & search filter | **PASS** | Table filtered down to `SOMNISENSE_E2E_TEST_20261125_225618` | [stage1_1_auth_04_patient_directory.png](evidence/stage1_1_auth_04_patient_directory.png) |
| **C4** | Persistence | Patient profile retrieval & DB persistence on reload | **PASS** | Entity verified on direct URL reload from PostgreSQL | [stage1_1_auth_05_patient_details.png](evidence/stage1_1_auth_05_patient_details.png) |
| **D1** | Dashboard Metrics | Overview cards rendering & live metrics contract | **PASS** | Total Patients: `1`, Needs Review: `—` (`Unavailable (AI/alerts API pending)`) | [stage1_1_auth_06_dashboard_stats.png](evidence/stage1_1_auth_06_dashboard_stats.png) |
| **E1** | Device Inventory | Device inventory & patient assignment dropdown | **PASS** | Synthetic patient selectable in device assignment list | [stage1_1_auth_07_device_inventory.png](evidence/stage1_1_auth_07_device_inventory.png) |
| **F1** | Sign Out | User sign out and redirect to `/login` | **PASS** | Terminated session; redirected to `/login` | [stage1_1_auth_08_logged_out.png](evidence/stage1_1_auth_08_logged_out.png) |
| **F2** | Route Protection | Protected route redirection when unauthenticated | **PASS** | Direct access to `/patients`, `/devices`, `/dashboard` redirects to `/login` | [stage1_1_auth_09_protected_redirect.png](evidence/stage1_1_auth_09_protected_redirect.png) |

---

## 4. Test Evidence Summary

All screenshots are recorded under `docs/integration/evidence/`:

1. [stage1_1_auth_01_dashboard.png](evidence/stage1_1_auth_01_dashboard.png) — TopBar role badge `ADMIN` and live dashboard rendering.
2. [stage1_1_auth_02_session_persisted.png](evidence/stage1_1_auth_02_session_persisted.png) — Successful session preservation across hard browser reload.
3. [stage1_1_auth_03_patient_created.png](evidence/stage1_1_auth_03_patient_created.png) — Confirmation card displaying generated patient code `PT-65GYJMHB`.
4. [stage1_1_auth_04_patient_directory.png](evidence/stage1_1_auth_04_patient_directory.png) — Patient directory search filtering down to synthetic record.
5. [stage1_1_auth_05_patient_details.png](evidence/stage1_1_auth_05_patient_details.png) — Direct URL retrieval of patient entity after hard reload.
6. [stage1_1_auth_06_dashboard_stats.png](evidence/stage1_1_auth_06_dashboard_stats.png) — Live summary cards with Total Patients: 1 and pending review placeholder.
7. [stage1_1_auth_07_device_inventory.png](evidence/stage1_1_auth_07_device_inventory.png) — Device inventory table and assignment select showing new patient.
8. [stage1_1_auth_08_logged_out.png](evidence/stage1_1_auth_08_logged_out.png) — Clean redirection to `/login` following user sign out.
9. [stage1_1_auth_09_protected_redirect.png](evidence/stage1_1_auth_09_protected_redirect.png) — Protected route interception redirecting unauthenticated attempts.
10. [stage1_1_unauth_login_redirect.png](evidence/stage1_1_unauth_login_redirect.png) — Initial pre-check unauthenticated redirect test.

---

## 5. QA Runner & Reproducibility Instructions

The test runner is committed to the team repository at `scripts/qa/stage1_1_authenticated_e2e.py`.

### Prerequisites:
- Python 3.10+
- Dependencies: `pip install -r scripts/qa/requirements.txt`
  - `playwright==1.65.2`
  - `greenlet>=3.1.1`
- Browser binaries: `playwright install chromium`

### Execution:
```powershell
# Interactive run (headed browser, prompts for admin1 password via getpass):
python scripts/qa/stage1_1_authenticated_e2e.py

# Headless run:
python scripts/qa/stage1_1_authenticated_e2e.py --headless
```

### Security Architecture of the Runner:
- **Interactive Prompt:** The password is input via Python `getpass.getpass()`, which suppresses terminal echo.
- **Process Memory Scope:** The password is held strictly in ephemeral process memory during form submission. References are dereferenced in a `finally:` block, and the process terminates immediately after test completion. (Note: In CPython, immutable `str` objects are not zeroized in C memory buffers; security relies on memory isolation, non-persistence, and immediate process exit).
- **Ephemeral Context:** The Playwright browser context runs in memory with no persistent profile directory on disk and is closed upon test completion.
- **Zero Disk Exposure:** No credentials, cookies, tokens, or authorization headers are logged to disk or reports.

---

## 6. Synthetic Test Data Status

During Stage 1.1 E2E verification, one synthetic patient entity was created:
- **Full Name:** `SOMNISENSE_E2E_TEST_20261125_225618`
- **Alias:** `E2E_SYNTH_20261125_225618`
- **Patient Code:** `PT-65GYJMHB`
- **Entity ID (UUID):** `132974f7-45b8-4e03-ac97-b4ddc68c6f0f`
- **Source Type:** `SIMULATED`
- **Status:** `ACTIVE`

**Retention Rationale:**  
The Spring Boot backend currently implements patient creation (`POST /api/v1/patients`), retrieval (`GET /api/v1/patients`), and assignment, but does **not** expose a patient hard-deletion or soft-deletion endpoint. Per change-control rules, direct out-of-band SQL deletions (`DELETE FROM patients WHERE id = ...`) were forbidden to preserve relational referential integrity. This record is intentionally retained as documented synthetic QA test data.

---

## 7. Known Limitations & Deferred Work

1. **AI Predictions & Alerts API Pending:**  
   Backend endpoints for sleep apnea event predictions and clinical alerts are not yet integrated into the Spring Boot backend. The frontend handles this gracefully by displaying `—` and `Unavailable (AI/alerts API pending)` on the Overview dashboard.
2. **Device Telemetry Stream Integration (Stage 2):**  
   Simulated signal generation from the Phase 9 S1–S3 simulator has not yet been bridged to Spring Boot device ingestion endpoints. Devices currently report static connectivity statuses.
3. **Password Management / Self-Service:**  
   User self-registration and password reset workflows are not part of the MVP scope and remain deferred to administrative workflows.

---

## 8. Final Git Change Summary

- **Total Files Committed:** 40 files (2,435 insertions, 288 deletions)
- **Frontend Source Changes:** 24 modified files (`frontend/src/`)
- **Documentation & Evidence:** 4 markdown reports, 10 screenshot PNGs (`docs/integration/`)
- **QA Automation:** 2 files (`scripts/qa/`)
- **Protected File Audit:**
  - `backend/` source and Flyway migrations `V1`–`V4`: **0 files modified (100% clean)**
  - Model C binary and metadata artifacts: **0 files modified (SHA-256 intact)**
  - Simulator S1–S3 engine: **0 files modified**
  - Dependency manifests (`package.json`, `package-lock.json`): **0 files modified**

---

## 9. Commit Verification

- **Branch:** `stage1/frontend-spring-boot-integration`
- **Commit Hash:** `a4e6e0ecd34e0e55791298483cd9af4ce86e0c37`
- **Commit Date:** 2026-11-25 23:01:28 +0530
- **Working Tree State:** Completely clean.
- **Push / Merge State:** Not pushed to remote; not merged into `main`; no pull request created.

---

## 10. Remaining Roadmap Items

Following completion of Stage 1 & 1.1:

1. **Stage 2: Virtual Simulator (Phase 9 S1–S3) ↔ Spring Boot Device Ingestion Bridge**
   - Package Phase 9 simulator engine as a telemetry producer.
   - Stream synthetic SpO2, heart rate, and respiratory signals into Spring Boot device ingestion endpoints (`POST /api/v1/devices/{id}/readings`).
2. **Stage 3: Real-Time Model C Apnea Inference Service Integration**
   - Connect the frozen Model C pipeline (`faef00b0...`) to live/simulated sliding-window telemetry streams.
   - Surface apnea predictions, confidence intervals, and review flags to the frontend dashboard.
3. **Stage 4: End-to-End System Validation & Clinical Evaluation**
   - Validate full multi-patient overnight simulation with live inference and alert generation.
