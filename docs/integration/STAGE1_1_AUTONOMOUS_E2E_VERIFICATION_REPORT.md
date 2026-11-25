# SOMNISENSE — Stage 1.1: Autonomous End-to-End Live Verification Report

> **Authoritative Repository:** `C:\Users\LORD\.gemini\antigravity\brain\8f3e7595-d599-46ca-a2e3-7fe4a6a02125\scratch\SomniSense_Team` (`https://github.com/AritraDas2003/SomniSense.git`)  
> **Branch:** `stage1/frontend-spring-boot-integration` (base `main` @ `47ee5a725a263e3c7266aeabd1e3e198a18742b6`)  
> **Verification Date:** 2026-11-25  
> **Overall Status:** **PARTIAL (Live unauthenticated browser ↔ Spring Boot integration, CORS, CSRF, and access control verified `PASS`; authenticated Tests B–F & DB query blocked by missing Chrome DevTools remote debugging `DevToolsActivePort` and unavailable `admin1`/`postgres` credentials in agent shell)**

---

## 1. Executive Summary

Autonomous live verification was executed against the running **PostgreSQL (`127.0.0.1:5432`)**, **Spring Boot backend (`http://127.0.0.1:8081`)**, and **Vite frontend (`http://127.0.0.1:5173`, `VITE_USE_MOCK_API=false`, `VITE_API_BASE_URL=http://127.0.0.1:8081/api/v1`)** stack without stopping any running service, altering code/migrations/models, or committing/pushing.

- **Verified Live (`PASS`):** Service availability & health (`200 UP`), live frontend configuration (`VITE_USE_MOCK_API="false"`), cross-origin CORS + `XSRF-TOKEN` cookie/header handshake (`200`), Spring Security unauthenticated rejection (`401 UNAUTHORIZED`, `403 CSRF_TOKEN_INVALID`), `POST /api/v1/auth/login` validation (`400 VALIDATION_FAILED` with CSRF), `POST /api/v1/auth/logout` with CSRF (`204 No Content`), and real headless Chromium SPA rendering of `/patients` redirecting cleanly to `/login` with **zero console errors**.
- **Blocked (`BLOCKED`):** Authenticated browser session verification (`admin1`), synthetic patient registration E2E, PostgreSQL `psql` row inspection, dashboard counts, and device management UI flows could not execute because:
  1. Chrome is running without remote debugging enabled (`C:\Users\LORD\AppData\Local\Google\Chrome\User Data\DevToolsActivePort` does not exist — `ENOENT`), preventing Chrome DevTools MCP from attaching to the existing `admin1` session.
  2. Neither `SOMNISENSE_ADMIN_PASSWORD` nor `DB_PASSWORD` / `backend/.env` is present in the agent shell environment (`fe_sendauth: no password supplied` on `psql -w`).

---

## 2. Environment & Service Verification (Test A)

| Component | Endpoint / Check | Observed Output / PID | Status |
|---|---|---|---|
| **PostgreSQL** | `postgresql-x64-19` Windows Service & `127.0.0.1:5432` | Service `Running`; `PG_5432: True` (TCP listening via `PID 26512`) | **PASS (Port Open)** *(direct `psql` query blocked on password)* |
| **Spring Boot Backend** | `GET http://127.0.0.1:8081/api/v1/health` | `PID 30552` listening on `::8081`; returns `HTTP 200`: `{"application":"SomniSense Backend","message":"Backend is running successfully","status":"UP"}` | **PASS** |
| **Vite Frontend** | `GET http://127.0.0.1:5173/` | `PID 9264` listening on `127.0.0.1:5173`; returns `HTTP 200` | **PASS** |
| **Frontend Live Config** | `GET http://127.0.0.1:5173/src/services/api/apiConfig.js` | `import.meta.env = {"VITE_API_BASE_URL": "http://127.0.0.1:8081/api/v1", "VITE_USE_MOCK_API": "false", ...}` | **PASS** |
| **Backend Logs** | File search in `SomniSense_Team` | `TEAM_LOG_FILES: []` (Spring Boot logs to its owner console window only; no `.log` file written under repo) | **UNKNOWN (Console-only)** |

---

## 3. Authentication Test Results (Test B)

| Check | Outcome | Observed Evidence |
|---|---|---|
| `GET /api/v1/auth/csrf` + CORS Preflight | **PASS** | `OPTIONS /api/v1/auth/login` (`Origin: http://127.0.0.1:5173`) → `HTTP 200`, `Access-Control-Allow-Origin: http://127.0.0.1:5173`, `Access-Control-Allow-Credentials: true`, `Access-Control-Allow-Headers: content-type, x-xsrf-token`. `GET /api/v1/auth/csrf` → `HTTP 200`, sets `XSRF-TOKEN` cookie and `X-Correlation-Id`. |
| `POST /api/v1/auth/login` (CSRF + Validation Path) | **PASS** *(validation)* / **BLOCKED** *(authenticated `admin1`)* | Sending `{}` with valid `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header returned `HTTP 400` (`code: "VALIDATION_FAILED"`, `fieldErrors` on `login` and `password`). Full `admin1` credential login blocked (no password env var; `DevToolsActivePort` unavailable). |
| `GET /api/v1/auth/me` (Unauthenticated Boot) | **PASS** | Returns `HTTP 401` (`code: "UNAUTHORIZED"`); headless Chromium did **not** display `.session-expired-notice` falsely on cold `/patients` visit. |
| `admin1` Role Recognition, Refresh Restore & Nav | **BLOCKED** | Chrome DevTools MCP (`browser` subagent) returned `ENOENT` on `%LOCALAPPDATA%\Google\Chrome\User Data\DevToolsActivePort` (`DEVTOOLS_ACTIVE_PORT: False`). Existing `admin1` session could not be attached without enabling Chrome remote debugging or supplying a test password. |

---

## 4. Patient Registration Test Results (Test C)

- **Status:** **BLOCKED**
- **Reason:** Requires an authenticated `ADMIN` or `OPERATOR` session (`POST /api/v1/patients` returns `401`/`403` unauthenticated). Verified UI route behavior for `/patients/new` and `/patients`: unauthenticated browser access cleanly triggers `/auth/csrf` → `/auth/me` (`401`) → redirect to `/login`.

---

## 5. Database Persistence Verification (Test D)

- **Status:** **BLOCKED**
- **Reason:** PostgreSQL `postgresql-x64-19` (`PID 26512`) is listening on `127.0.0.1:5432`, but non-interactive `psql -h 127.0.0.1 -p 5432 -U postgres -d somnisense_db -w` exited with code `2`: `psql: error: connection to server at "127.0.0.1", port 5432 failed: fe_sendauth: no password supplied`. No `.pgpass`, `backend/.env`, or `DB_PASSWORD` environment variable is exposed to the agent shell.

---

## 6. Dashboard Integration Results (Test E)

- **Status:** **BLOCKED**
- **Reason:** `/dashboard` requires an authenticated staff session (`GET /patients`, `GET /sessions`, `GET /devices` return `401 UNAUTHORIZED` without `JSESSIONID`).

---

## 7. Device Management Results (Test F)

- **Status:** **BLOCKED**
- **Reason:** `/devices` requires an authenticated staff session (`GET /devices` returns `401 UNAUTHORIZED` without `JSESSIONID`).

---

## 8. Logout & Access-Control Results (Test G)

| Endpoint / Flow | Request Method / Context | HTTP Status & Sanitized Response | Outcome |
|---|---|---|---|
| Unauthenticated `GET /api/v1/patients` | Python HTTP client (no cookies) | `401` (`code: "UNAUTHORIZED"`, `"Authentication is required"`, `correlationId` present) | **PASS** |
| Unauthenticated `GET /api/v1/devices` | Python HTTP client (no cookies) | `401` (`code: "UNAUTHORIZED"`) | **PASS** |
| Unauthenticated `GET /api/v1/users` | Python HTTP client (no cookies) | `401` (`code: "UNAUTHORIZED"`) | **PASS** |
| Unauthenticated `GET /api/v1/auth/me` | Python HTTP client (no cookies) | `401` (`code: "UNAUTHORIZED"`) | **PASS** |
| Unauthenticated `POST /api/v1/patients` (no CSRF) | Python HTTP client (no cookies/CSRF) | `403` (`code: "CSRF_TOKEN_INVALID"`, `"Missing or invalid CSRF token"`) | **PASS** |
| Unauthenticated `POST /api/v1/auth/logout` (no CSRF) | Python HTTP client (no cookies/CSRF) | `403` (`code: "CSRF_TOKEN_INVALID"`, `"Missing or invalid CSRF token"`) | **PASS** |
| `POST /api/v1/auth/logout` (valid `XSRF-TOKEN` cookie + header) | Python HTTP client (separate context) | **`204 No Content`** | **PASS** |
| Unauthenticated Browser Route Guard (`/patients`) | Isolated Headless Chrome (`1280x800`) | Redirected cleanly to `http://127.0.0.1:5173/login`; `LoginPage` rendered `"Username or work email"` + `"Staff access only · Live backend"` | **PASS** |
| Authenticated Session Invalidation After Logout | Separate browser context | Requires live login session (`admin1` or test operator) | **BLOCKED** |

---

## 9. Browser Console & Backend Log Findings

- **Headless Chromium Console Errors (`BROWSER_CONSOLE_ERRORS`):** `[]` (**0** JS exceptions, **0** `console.error` calls).
- **Observed Browser API Calls to `:8081`:**
  - `GET http://127.0.0.1:8081/api/v1/auth/csrf` → `200`
  - `OPTIONS` preflight + `GET http://127.0.0.1:8081/api/v1/auth/me` → `200` (preflight) / `401` (`UNAUTHORIZED` GET)
  - Note: React `<StrictMode>` in Vite dev mode invokes `AuthBootstrap`'s `useEffect` twice on mount (`auth/csrf` and `auth/me` each executed twice—expected React 19 dev behavior).
- **Spring Boot Logs:** No `.log` files exist inside `SomniSense_Team` (`TEAM_LOG_FILES: []`); Spring Boot output is written only to the user's interactive console process (`PID 30552`).

---

## 10. Test Data Created & Cleanup Status

- **Synthetic Patient / Device Records Created:** **None** (authenticated tests `C` and `F` could not execute).
- **Temporary QA Artifacts:** Isolated headless Chromium profile (`chrome_qa_tmp`) was deleted immediately upon completion (`shutil.rmtree`); temporary QA script `scratch/run_headless_browser_qa.py` lives outside the repository in scratch; visual screenshot saved under `docs/integration/evidence/stage1_1_unauth_login_redirect.png`.

---

## 11. Test Execution Summary Matrix

| Test | Title | Status | Notes |
|---|---|---|---|
| **Test A** | Service availability & live config | **PASS** *(DB auth blocked)* | PostgreSQL `5432` running; Spring Boot `8081` `/api/v1/health` `200 UP`; Vite `5173` `200` with `VITE_USE_MOCK_API=false`, `VITE_API_BASE_URL=http://127.0.0.1:8081/api/v1`; backend log file not present. |
| **Test B** | Authentication & navigation | **PARTIAL / BLOCKED** | `GET /auth/csrf` `200`, CORS `Allow-Credentials: true`, `POST /auth/login` CSRF+validation (`400`), `GET /auth/me` `401` passed; `admin1` login/refresh/navigation blocked (`DevToolsActivePort` missing & no password env var). |
| **Test C** | Patient registration | **BLOCKED** | Blocked pending authenticated session (`admin1` / test operator credentials). |
| **Test D** | Database persistence | **BLOCKED** | Blocked pending `DB_PASSWORD` / `.pgpass` for `psql` against `somnisense_db`. |
| **Test E** | Dashboard integration | **BLOCKED** | Blocked pending authenticated session. |
| **Test F** | Device management | **BLOCKED** | Blocked pending authenticated session. |
| **Test G** | Logout & access control | **PARTIAL (`PASS` unauth / `BLOCKED` authed)** | All unauthenticated `401` / CSRF `403` / valid-CSRF `POST /auth/logout` `204` / headless Chrome `/patients -> /login` checks **PASSED**; authenticated session invalidation post-logout blocked. |

---

## 12. Evidence & Visual Verification

- **Headless Chromium Screenshot (1280×800):** [stage1_1_unauth_login_redirect.png](file:///C:/Users/LORD/.gemini/antigravity/brain/8f3e7595-d599-46ca-a2e3-7fe4a6a02125/scratch/SomniSense_Team/docs/integration/evidence/stage1_1_unauth_login_redirect.png)
  - Confirms live Vite render at `http://127.0.0.1:5173/login` after redirecting from `/patients`.
  - Confirms `"Username or work email"`, `"Staff access only · Live backend"`, absence of `"MOCK DATA"` pill / demo credentials, and absence of a false `"Session expired"` alert on initial load.

---

## 13. Outstanding Defects & Environment Blockers

- **Code Defects Found (in executed paths):** **None.** Axios `withCredentials`, `XSRF-TOKEN` / `X-XSRF-TOKEN` exchange, CORS headers, Spring Security unauthenticated rejections, and unauthenticated SPA route guarding operated cleanly with `0` console errors.
- **Environment Blockers Preventing Authenticated E2E:**
  1. **Chrome Remote Debugging Disabled:** `%LOCALAPPDATA%\Google\Chrome\User Data\DevToolsActivePort` is missing (`ENOENT`), preventing attachment to the active `admin1` Chrome session.
  2. **Credentials Unavailable to Agent Shell:** `DB_PASSWORD` and `SOMNISENSE_ADMIN_PASSWORD` / QA account credentials are only in the user's running terminal/browser, not in `backend/.env` or agent environment variables.
  3. **Backend Console-Only Logging:** Spring Boot (`PID 30552`) has no file log configured (`logging.file.name` unset), so server-side stack/debug logs could not be inspected from disk.

---

## 14. Recommended Next Milestone

1. **Unblock & Complete Authenticated Stage 1.1 Verification:** Either enable remote debugging in Chrome (`chrome://inspect/#remote-debugging`) so the existing `admin1` session can be inspected via DevTools MCP, or provide a dedicated non-production QA test account + `logging.file.name` in local runtime config so `Tests B–F` and authenticated `Test G` can run autonomously to `PASS`.
2. **Proceed to Stage 2 (Backend `predictions` Persistence + API + `V5` Flyway Migration):** Once authenticated Stage 1.1 checks pass, authorize Stage 2 per the roadmap.

---

## 15. Git Branch & Working-Tree Status

- **Repository:** `C:\Users\LORD\.gemini\antigravity\brain\8f3e7595-d599-46ca-a2e3-7fe4a6a02125\scratch\SomniSense_Team`
- **Branch:** `stage1/frontend-spring-boot-integration`
- **Base Commit:** `47ee5a725a263e3c7266aeabd1e3e198a18742b6`
- **Status:** All 24 Stage 1 frontend edits remain intact (`M frontend/src/...`); `docs/` contains the integration reports and screenshot (`?? docs/`); zero backend Java, Flyway (`V1`–`V4`), `package.json`, `package-lock.json`, Model C, or Phase 9 simulator files were modified; nothing committed or pushed.
