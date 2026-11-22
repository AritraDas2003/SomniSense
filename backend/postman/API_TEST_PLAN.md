# SomniSense Backend — Complete API Test Plan (for a Postman AI agent)

Give this whole file to the agent. It lists every endpoint of the Spring Boot backend with the exact expected
result, in a safe execution order. Existing collection: `SomniSense.postman_collection.json` (covers the happy
path); this plan is the **complete** checklist, including all failure cases.

> Educational prototype. All data created by these tests is synthetic. Do not use real patient data.

---

## 0. Agent instructions

1. **Base URL:** `http://localhost:8080/api/v1` (variable `baseUrl`). The backend must already be running.
2. **Two kinds of authentication**
   - **Staff APIs:** session cookie (`JSESSIONID`, set by login, Postman keeps it automatically) **plus** a CSRF
     header on every `POST` / `PUT` / `PATCH` / `DELETE`: `X-XSRF-TOKEN: <token>`.
     Get the token with `GET /auth/csrf` (response `{"headerName":"X-XSRF-TOKEN","token":"..."}`) and store it
     in `xsrfToken`. **Login issues a NEW token**, so call `GET /auth/csrf` again after every login.
   - **Ingestion API:** header `X-Ingestion-Key: {{ingestionKey}}` only. No cookie, no CSRF.
3. **Secrets** come from the Postman environment (`adminPassword`, `ingestionKey`, both secret type). Never print
   or save them in test output or exports.
4. **Roles:** `ADMIN` (the seeded admin) and `OPERATOR` (created in section 2). Test every ADMIN-only endpoint as
   OPERATOR too (expect 403).
5. **Error shape** (every error, including 401/403):
   `{"timestamp","status","error","code","message","path","correlationId","fieldErrors":[]}`.
   Assert on `code` (and `status`), **never on `message` text**. Every response has an `X-Correlation-Id` header.
6. **Lists** return `{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0}`. `page` is zero-based.
7. **Timestamps** are ISO-8601 UTC (`...Z`). IDs are UUIDs.
8. **Never expected anywhere:** a `password`, `passwordHash`, `secret` or `token` field in a response body
   (except `temporaryPassword` in the two places noted).
9. Run sections **in order**: later sections reuse IDs saved by earlier ones. Save IDs in collection variables:
   `patientId, patientCode, deviceId, deviceCode, assignmentId, sessionId, sessionCode, operatorId`.
10. Use the suffix `{{runId}}` (e.g. a timestamp) in usernames/emails/names so reruns do not collide.

Legend: ✅ = expected success. ❌ = expected failure (assert the status **and** `code`).

---

## 1. Health (public, no login)

| # | Method + URL | Expected |
|---|---|---|
| 1.1 | `GET /health` | ✅ 200, `status` = `UP`, has `application` and `message` |
| 1.2 | `GET http://localhost:8080/actuator/health` (not under `/api/v1`) | ✅ 200, `status` = `UP`, **no** `components`/`details` (details are hidden) |
| 1.3 | `GET /does-not-exist` (logged out) | ❌ 401 `UNAUTHORIZED` |

## 2. Authentication (`/auth`)

| # | Method + URL | Body / header | Expected |
|---|---|---|---|
| 2.1 | `GET /auth/csrf` | none | ✅ 200, `token` non-empty; sets cookie `XSRF-TOKEN`. Store `xsrfToken` |
| 2.2 | `GET /auth/me` | not logged in | ❌ 401 `UNAUTHORIZED` |
| 2.3 | `POST /auth/login` | `{"login":"admin","password":"{{adminPassword}}"}`, **no** CSRF header | ❌ 403 `CSRF_TOKEN_INVALID` |
| 2.4 | `POST /auth/login` | wrong password, with CSRF | ❌ 401 `INVALID_CREDENTIALS` |
| 2.5 | `POST /auth/login` | unknown user `nobody-{{runId}}`, with CSRF | ❌ 401 `INVALID_CREDENTIALS` and the **same message as 2.4** (no user enumeration) |
| 2.6 | `POST /auth/login` | `{"login":"","password":""}`, with CSRF | ❌ 400 `VALIDATION_FAILED`, `fieldErrors` has `login` and `password` |
| 2.7 | `POST /auth/login` | body `{not json`, with CSRF | ❌ 400 `MALFORMED_REQUEST` |
| 2.8 | `POST /auth/login` | valid admin credentials, with CSRF | ✅ 200; body has `id, username, email, role=ADMIN, active=true, lastLoginAt, createdAt`; **no** password fields; response sets `JSESSIONID` (HttpOnly). Save `adminUserId` |
| 2.9 | `GET /auth/csrf` | after login | ✅ 200. **Refresh `xsrfToken`** |
| 2.10 | `GET /auth/me` | logged in | ✅ 200, same user as 2.8 |
| 2.11 | `POST /auth/login` | login by **email** (`admin@somnisense.local`, or whatever the seeded admin email is) | ✅ 200 (login accepts username or email, case-insensitive) |

Password change (do this with the **operator** created in section 3, not the admin):

| # | Method + URL | Body | Expected |
|---|---|---|---|
| 2.12 | `POST /auth/password` | `{"currentPassword":"WRONG-wrong-1","newPassword":"NewPassw0rd-{{runId}}"}` | ❌ 400 `CURRENT_PASSWORD_INCORRECT` (the user must NOT be logged out) |
| 2.13 | `POST /auth/password` | `newPassword` shorter than 12 chars | ❌ 400 `VALIDATION_FAILED`, field `newPassword` |
| 2.14 | `POST /auth/password` | new = current | ❌ 400 `INVALID_REQUEST` |
| 2.15 | `POST /auth/password` | correct current, valid new (12–72 chars) | ✅ 204 no body; `GET /auth/me` still works in the same session |
| 2.16 | `POST /auth/password` | not logged in | ❌ 401 `UNAUTHORIZED` |

Logout is tested last (section 12).

## 3. Staff management (`/users`, ADMIN only)

Log in as **ADMIN** first (2.8, then 2.9).

| # | Method + URL | Body | Expected |
|---|---|---|---|
| 3.1 | `POST /users` | `{"username":"ops.{{runId}}","email":"ops.{{runId}}@demo.local","role":"OPERATOR"}` | ✅ 201, `Location` header, body `{"user":{...},"temporaryPassword":"<16 chars>"}`. `user.username` is lower-case, `active=true`, `lastLoginAt=null`. Save `operatorId`; keep `temporaryPassword` in a **secret** variable `operatorPassword` for 3.18 and delete it at the end |
| 3.2 | `POST /users` | same username again | ❌ 409 `CONFLICT` |
| 3.3 | `POST /users` | same email, different username | ❌ 409 `CONFLICT` |
| 3.4 | `POST /users` | `{"username":"a@b","email":"not-an-email"}` | ❌ 400 `VALIDATION_FAILED`; `fieldErrors` fields include `username`, `email`, `role` |
| 3.5 | `POST /users` | `"role":"SUPERUSER"` | ❌ 400 `MALFORMED_REQUEST` |
| 3.6 | `GET /users` | – | ✅ 200, a page; items have **no** `password*` field |
| 3.7 | `GET /users?role=OPERATOR&active=true&q=ops&page=0&size=5&sort=username&direction=asc` | – | ✅ 200, all items `role=OPERATOR`, sorted by username ascending, `size`=5 |
| 3.8 | `GET /users?size=500` | – | ❌ 400 `VALIDATION_FAILED` |
| 3.9 | `GET /users?sort=passwordHash` | – | ❌ 400 `INVALID_REQUEST` |
| 3.10 | `GET /users?direction=sideways` | – | ❌ 400 `INVALID_REQUEST` |
| 3.11 | `GET /users/{{operatorId}}` | – | ✅ 200 |
| 3.12 | `GET /users/00000000-0000-0000-0000-000000000001` | – | ❌ 404 `RESOURCE_NOT_FOUND` |
| 3.13 | `GET /users/not-a-uuid` | – | ❌ 400 |
| 3.14 | `PATCH /users/{{operatorId}}/role` | `{"role":"OPERATOR"}` (already operator) | ✅ 200, no change |
| 3.15 | `PATCH /users/{{adminUserId}}/role` | `{"role":"OPERATOR"}` | ❌ 400 `SELF_ACTION_NOT_ALLOWED` |
| 3.16 | `POST /users/{{adminUserId}}/deactivate` | – | ❌ 400 `SELF_ACTION_NOT_ALLOWED` |
| 3.17 | `POST /users/{{adminUserId}}/reset-password` | – | ❌ 400 `SELF_ACTION_NOT_ALLOWED` |
| 3.18 | **Operator login:** switch identity: `GET /auth/csrf`, `POST /auth/login` with `{"login":"ops.{{runId}}","password":"{{operatorPassword}}"}` | – | ✅ 200, `role=OPERATOR`. (Use a separate cookie jar / log in as operator, then log back in as admin.) |
| 3.19 | As **OPERATOR**: `GET /users`, `POST /users`, `PATCH /users/{id}/role`, `POST /users/{id}/deactivate` | – | ❌ 403 `FORBIDDEN` each |
| 3.20 | As ADMIN: `POST /users/{{operatorId}}/reset-password` | – | ✅ 200 `{"temporaryPassword":"<16 chars>"}`. The operator's old password stops working: login with it → 401 |
| 3.21 | As ADMIN: `POST /users/{{operatorId}}/deactivate` (twice) | – | ✅ 200 `active=false`; the second call also 200 (no change) |
| 3.22 | Login as the deactivated operator | – | ❌ 401 `INVALID_CREDENTIALS` |
| 3.23 | As ADMIN: `POST /users/{{operatorId}}/reactivate` | – | ✅ 200 `active=true` |

**Last-admin rule** (only if a second ADMIN exists; do not leave the system with zero active admins):
create a second admin via 3.1 with `"role":"ADMIN"`, then, as the first admin, deactivate it ✅ 200. You can **not**
deactivate the only remaining admin (3.16 covers the self case); the multi-admin race is covered by the Java tests.

## 4. Patients (`/patients`, ADMIN + OPERATOR)

Log in as OPERATOR for these (except 4.19–4.21, which need ADMIN).

| # | Method + URL | Body | Expected |
|---|---|---|---|
| 4.1 | `POST /patients` | `{"fullName":"  Postman Patient {{runId}} ","alias":"PM-{{runId}}","dateOfBirth":"1980-05-17","gender":"FEMALE","sourceType":"SIMULATED","patientCode":"PT-HACKED00"}` | ✅ 201 + `Location`; `patientCode` matches `^PT-[2-9A-HJ-NP-Z]{8}$` and is **not** `PT-HACKED00`; `fullName` trimmed; `status=ACTIVE`; `contact=null`; `medicalDetails=null`; `currentDevice=null`; `activeSession=null`; `latestReading=null`; `createdBy` = operator id. Save `patientId`, `patientCode` |
| 4.2 | `POST /patients` | with nested `contact` and `medicalDetails` (`{"fullName":"P2 {{runId}}","sourceType":"PHYSICAL","contact":{"phone":"+91 98300 12345","email":"P@Example.Local","city":"Kolkata"},"medicalDetails":{"sleepDisorder":"Suspected OSA","notes":"Synthetic note"}}`) | ✅ 201; `contact.email` is lower-case; `medicalDetails.updatedBy` set. Save `physicalPatientId` |
| 4.3 | `POST /patients` | `{"alias":"x","dateOfBirth":"2999-01-01","contact":{"phone":"call me","email":"nope"},"medicalDetails":{"sleepDisorder":"<151 chars>"}}` | ❌ 400 `VALIDATION_FAILED`; fields include `fullName`, `sourceType`, `dateOfBirth`, `contact.phone`, `contact.email`, `medicalDetails.sleepDisorder` |
| 4.4 | `POST /patients` | `"dateOfBirth":"1850-01-01"` (valid otherwise) | ❌ 400 `INVALID_REQUEST` |
| 4.5 | `POST /patients` | `"sourceType":"HARDWARE"` or `"gender":"M"` | ❌ 400 `MALFORMED_REQUEST` |
| 4.6 | `GET /patients` | – | ✅ 200 page; list items have `id, patientCode, fullName, alias, sourceType, status, currentDevice, activeSession, latestReading, createdAt` and **no** `dateOfBirth`, `contact`, `medicalDetails` |
| 4.7 | `GET /patients?q={{patientCode}}` | – | ✅ 200, `totalElements`=1 (search is case-insensitive over code, name, alias) |
| 4.8 | `GET /patients?sourceType=SIMULATED&status=ACTIVE&sort=fullName&direction=asc&size=2&page=0` | – | ✅ 200, filtered, sorted, 2 per page |
| 4.9 | `GET /patients?q=%25` | – | ✅ 200 with 0 results (`%` is matched literally) |
| 4.10 | `GET /patients?sort=dateOfBirth` | – | ❌ 400 `INVALID_REQUEST` |
| 4.11 | `GET /patients?size=101` | – | ❌ 400 `VALIDATION_FAILED` |
| 4.12 | `GET /patients/{{patientId}}` | – | ✅ 200 full detail incl. `dateOfBirth`, `gender` |
| 4.13 | `GET /patients/00000000-0000-0000-0000-000000000001` | – | ❌ 404 `RESOURCE_NOT_FOUND` |
| 4.14 | `PUT /patients/{{patientId}}` | `{"fullName":"Postman Patient Renamed","gender":"OTHER","sourceType":"PHYSICAL"}` | ✅ 200; `fullName` changed; `sourceType` still `SIMULATED` and `patientCode` unchanged (immutable) |
| 4.15 | `PUT /patients/{{patientId}}` | `{}` | ❌ 400 `VALIDATION_FAILED` (`fullName`) |
| 4.16 | `PUT /patients/{{patientId}}/contact` | `{"phone":"111","city":"Pune"}` | ✅ 200; then `PUT` with `{"phone":"222"}` → `city` is now `null` (PUT replaces) |
| 4.17 | `PUT /patients/{{patientId}}/contact` | `{"phone":"abc","email":"bad"}` | ❌ 400 `VALIDATION_FAILED` |
| 4.18 | `PUT /patients/{{patientId}}/medical-details` | `{"medications":"none","notes":"Synthetic"}` | ✅ 200; `medicalDetails.updatedBy`/`updatedAt` set |
| 4.19 | As **OPERATOR**: `PATCH /patients/{{patientId}}/status` | `{"status":"INACTIVE"}` | ❌ 403 `FORBIDDEN` |
| 4.20 | As **ADMIN**: `PATCH /patients/{{physicalPatientId}}/status` | `{"status":"INACTIVE"}` | ✅ 200 `status=INACTIVE`; then `GET /patients?status=INACTIVE` includes it |
| 4.21 | As ADMIN: `PATCH /patients/{{physicalPatientId}}/status` | `{"status":"ACTIVE"}` | ✅ 200 |
| 4.22 | Not logged in: `GET /patients` | – | ❌ 401 |

## 5. Devices (`/devices`, ADMIN + OPERATOR; deactivate/reactivate ADMIN only)

| # | Method + URL | Body | Expected |
|---|---|---|---|
| 5.1 | `POST /devices` | `{"deviceType":"SIMULATED","modelName":"Postman virtual oximeter"}` | ✅ 201; `deviceCode` matches `^DEV-[2-9A-HJ-NP-Z]{8}$`; `enrollmentStatus=ENROLLED`; `connectivityStatus=UNKNOWN`; `lastSeenAt=null`; `active=true`; `currentAssignment=null`. Save `deviceId`, `deviceCode` |
| 5.2 | `POST /devices` | `{"deviceCode":" oxi-{{runId}} ","deviceType":"PHYSICAL","serialNumber":"SN-{{runId}}"}` | ✅ 201; `deviceCode` upper-cased and trimmed; `enrollmentStatus=PENDING`. Save `physicalDeviceId` |
| 5.3 | `POST /devices` | same `deviceCode` in different case, e.g. lower-case | ❌ 409 `CONFLICT` |
| 5.4 | `POST /devices` | `{"deviceCode":"has space!","modelName":"<101 chars>"}` | ❌ 400 `VALIDATION_FAILED`; fields `deviceCode`, `deviceType`, `modelName` |
| 5.5 | `POST /devices` | `"deviceType":"HARDWARE"` | ❌ 400 `MALFORMED_REQUEST` |
| 5.6 | `POST /devices` | extra fields `"secret":"abc","credential":"xyz"` | ✅ 201 (ignored); the **response must not contain** `secret`/`credential`/`abc`/`xyz` |
| 5.7 | `GET /devices` | – | ✅ 200 page; each item has `active, enrollmentStatus, connectivityStatus, lastSeenAt, currentAssignment` |
| 5.8 | `GET /devices?deviceType=SIMULATED&enrollmentStatus=ENROLLED&active=true&connectivity=UNKNOWN&q=postman&sort=deviceCode&direction=asc` | – | ✅ 200, filtered |
| 5.9 | `GET /devices?sort=serialNumber` | – | ❌ 400 `INVALID_REQUEST` |
| 5.10 | `GET /devices?connectivity=ONLINE` | – | ❌ 400 |
| 5.11 | `GET /devices/{{deviceId}}` | – | ✅ 200 |
| 5.12 | `GET /devices/00000000-0000-0000-0000-000000000001` | – | ❌ 404 |
| 5.13 | `PUT /devices/{{deviceId}}` | `{"modelName":"New model","serialNumber":"S1","deviceCode":"HACK","deviceType":"PHYSICAL"}` | ✅ 200; `modelName`/`serialNumber` changed; `deviceCode` and `deviceType` **unchanged** |
| 5.14 | `PATCH /devices/{{physicalDeviceId}}/enrollment` | `{"enrollmentStatus":"ENROLLED"}` | ✅ 200 (repeat → 200 no change) |
| 5.15 | `PATCH /devices/{{deviceId}}/enrollment` | `{"enrollmentStatus":"BOGUS"}` | ❌ 400 `MALFORMED_REQUEST` |
| 5.16 | As **OPERATOR**: `POST /devices/{{physicalDeviceId}}/deactivate` | – | ❌ 403 `FORBIDDEN` |
| 5.17 | As **ADMIN**: `POST /devices/{{physicalDeviceId}}/deactivate` (twice) | – | ✅ 200 `active=false` (second call also 200) |
| 5.18 | `GET /devices?active=false` | – | ✅ includes the physical device |
| 5.19 | As ADMIN: `POST /devices/{{physicalDeviceId}}/reactivate` | – | ✅ 200 `active=true` |
| 5.20 | As OPERATOR: `POST /devices/{{physicalDeviceId}}/reactivate` | – | ❌ 403 |

## 6. Device assignments (`/device-assignments`, ADMIN + OPERATOR)

Uses the SIMULATED patient (`patientId`) and SIMULATED device (`deviceId`); the PHYSICAL patient/device are used for mismatch tests.

| # | Method + URL | Body | Expected |
|---|---|---|---|
| 6.1 | `POST /device-assignments` | `{}` | ❌ 400 `VALIDATION_FAILED` (`deviceId`, `patientId`) |
| 6.2 | `POST /device-assignments` | unknown device UUID | ❌ 404 `RESOURCE_NOT_FOUND` |
| 6.3 | `POST /device-assignments` | unknown patient UUID | ❌ 404 |
| 6.4 | `POST /device-assignments` | SIMULATED device + **PHYSICAL** patient | ❌ 400 `SOURCE_TYPE_MISMATCH` |
| 6.5 | `POST /device-assignments` | PHYSICAL device that is `PENDING` or inactive + PHYSICAL patient | ❌ 409 `DEVICE_NOT_AVAILABLE` |
| 6.6 | `POST /device-assignments` | device + an `INACTIVE` patient (set one INACTIVE first via 4.20, assign, then set back ACTIVE) | ❌ 409 `PATIENT_NOT_ACTIVE` |
| 6.7 | `POST /device-assignments` | `{"deviceId":"{{deviceId}}","patientId":"{{patientId}}"}` | ✅ 201 + `Location`; `active=true`, `releasedAt=null`, `assignedBy` = acting user, `deviceCode`/`patientCode` populated. Save `assignmentId` |
| 6.8 | repeat 6.7 exactly | – | ✅ **200** (not 201), same `id` |
| 6.9 | `GET /device-assignments/{{assignmentId}}` | – | ✅ 200 |
| 6.10 | `GET /devices/{{deviceId}}` | – | ✅ `currentAssignment.patientCode` = `{{patientCode}}` |
| 6.11 | `GET /patients/{{patientId}}` | – | ✅ `currentDevice.deviceCode` = `{{deviceCode}}`, has `connectivityStatus` |
| 6.12 | `GET /device-assignments?deviceId={{deviceId}}&active=true&page=0&size=10` | – | ✅ 200, newest first |
| 6.13 | As ADMIN: `POST /devices/{{deviceId}}/deactivate` | – | ❌ 409 `DEVICE_IN_USE` |
| 6.14 | As ADMIN: `PATCH /patients/{{patientId}}/status` `{"status":"INACTIVE"}` | – | ❌ 409 `PATIENT_HAS_ACTIVE_DEVICE` |
| 6.15 | Create a **second** SIMULATED patient `patient2` and device `device2`. `POST` assign `deviceId` → `patient2` (no `replaceExisting`) | – | ❌ 409 `DEVICE_ALREADY_ASSIGNED` (message names the other patient's **code**) |
| 6.16 | assign `device2` → `patientId` (patient already has a device) | – | ❌ 409 `PATIENT_ALREADY_HAS_DEVICE` |
| 6.17 | assign `deviceId` → `patient2` with `"replaceExisting":true` | – | ✅ 201; the old assignment is now `active=false` with `releasedBy`/`releasedAt` set; history list for the device shows **2** rows, newest first |
| 6.18 | `POST /device-assignments/{{oldAssignmentId}}/release` (already released) | – | ✅ 200 `active=false` (idempotent) |
| 6.19 | `POST /device-assignments/00000000-0000-0000-0000-000000000001/release` | – | ❌ 404 |
| 6.20 | re-assign `deviceId` → `patientId` so the main flow continues; save the new `assignmentId` | – | ✅ 201 (release `patient2`'s assignment first if needed) |

## 7. Monitoring sessions (`/sessions`, ADMIN + OPERATOR)

| # | Method + URL | Body | Expected |
|---|---|---|---|
| 7.1 | `POST /sessions` | `{}` | ❌ 400 `VALIDATION_FAILED` |
| 7.2 | `POST /sessions` | unknown `patientId` | ❌ 404 |
| 7.3 | `POST /sessions` | a patient **without** an assignment | ❌ 409 `NO_ACTIVE_ASSIGNMENT` |
| 7.4 | `POST /sessions` | `{"patientId":"{{patientId}}","deviceAssignmentId":"00000000-0000-0000-0000-000000000009"}` | ❌ 409 `ASSIGNMENT_MISMATCH` |
| 7.5 | `POST /sessions` | `{"patientId":"{{patientId}}","startedBy":"11111111-1111-1111-1111-111111111111"}` | ✅ 201 + `Location`; `sessionCode` matches `^SES-\d{8}-[2-9A-HJ-NP-Z]{6}$` (date = today UTC); `status=ACTIVE`; `sourceType=SIMULATED`; `endedAt=null`; `durationSeconds` ≥ 0; **`startedBy` = the logged-in user, not the one sent**. Save `sessionId`, `sessionCode` |
| 7.6 | repeat 7.5 (double click) | – | ✅ **200**, same `id` |
| 7.7 | `GET /sessions/{{sessionId}}` | – | ✅ 200 |
| 7.8 | `GET /patients/{{patientId}}` | – | ✅ `activeSession.sessionId` = `{{sessionId}}` |
| 7.9 | `POST /device-assignments/{{assignmentId}}/release` | – | ❌ 409 `SESSION_ACTIVE` |
| 7.10 | assign with `replaceExisting:true` to another patient while the session is active | – | ❌ 409 `SESSION_ACTIVE` |
| 7.11 | `GET /sessions?patientId={{patientId}}&status=ACTIVE&page=0&size=10` | – | ✅ 200, newest first |
| 7.12 | `GET /sessions?startedFrom=2030-01-01T00:00:00Z&startedTo=2020-01-01T00:00:00Z` | – | ❌ 400 `INVALID_REQUEST` (from not before to) |
| 7.13 | `GET /sessions?status=BOGUS` | – | ❌ 400 |
| 7.14 | `GET /sessions/00000000-0000-0000-0000-000000000001` | – | ❌ 404 |

Continue with sections 8 and 9 while this session is ACTIVE, then end it:

| # | Method + URL | Expected |
|---|---|---|
| 7.15 | `POST /sessions/{{sessionId}}/end` (after section 9) | ✅ 200 `status=ENDED`, `endedBy` = acting user, `endedAt` set |
| 7.16 | repeat end | ✅ 200 (no change) |
| 7.17 | `POST /sessions/{{sessionId}}/cancel` on the ENDED session | ❌ 409 `INVALID_SESSION_TRANSITION` |
| 7.18 | Start a **new** session on the same patient, then `POST .../cancel` | ✅ 200 `status=CANCELLED`; then `.../end` → ❌ 409 `INVALID_SESSION_TRANSITION` |
| 7.19 | `POST /sessions/00000000-0000-0000-0000-000000000001/end` | ❌ 404 |
| 7.20 | After the session is ENDED: `POST /device-assignments/{{assignmentId}}/release` | ✅ 200 |

## 8. Reading ingestion (`POST /ingestion/readings`, machine key only)

Requires an ACTIVE session on the device (after 7.5). Header: `X-Ingestion-Key: {{ingestionKey}}`.
Use timestamps a few seconds in the past (`now − 1…10 s`, ISO UTC).

| # | Request | Expected |
|---|---|---|
| 8.1 | No key header | ❌ 401 `UNAUTHORIZED` |
| 8.2 | Wrong key | ❌ 401 `UNAUTHORIZED` |
| 8.3 | **Staff cookie + CSRF header, no key** (log in as admin first) | ❌ 401 (a logged-in browser cannot post readings) |
| 8.4 | `GET /patients` with **only** `X-Ingestion-Key` (no login) | ❌ 401 (the key gives no staff access) |
| 8.5 | Valid key, `{"deviceCode":"{{deviceCode}}","readings":[{"readingKey":"pm-{{runId}}-1","measuredAt":"<now-4s>","spo2":97,"heartRate":68},{"readingKey":"pm-{{runId}}-2","measuredAt":"<now-3s>","spo2":95.5,"heartRate":70},{"readingKey":"pm-{{runId}}-3","measuredAt":"<now-2s>","spo2":null,"heartRate":null},{"readingKey":"pm-{{runId}}-4","measuredAt":"<now-1s>","spo2":150,"heartRate":66}]}` | ✅ 200; `accepted`=3, `duplicates`=0, `rejected`=[`{index:3, readingKey:"…-4", reason:"SPO2_OUT_OF_RANGE"}`]; `sessionCode` = `{{sessionCode}}` |
| 8.6 | **Resend the identical body** | ✅ 200; `accepted`=0, `duplicates`=3, same one `rejected` |
| 8.7 | `deviceCode` in lower-case | ✅ 200 (case-insensitive) |
| 8.8 | Matching `"sessionCode":"{{sessionCode}}"` (any case) | ✅ 200 |
| 8.9 | `"sessionCode":"SES-OTHER"` | ❌ 409 `SESSION_MISMATCH` |
| 8.10 | `deviceCode":"DEV-NOPE"` | ❌ 404 `RESOURCE_NOT_FOUND` |
| 8.11 | A device with **no active session** (e.g. `device2` unassigned, or after the session is ended) | ❌ 409 `NO_ACTIVE_SESSION` |
| 8.12 | `"readings":[]` | ❌ 400 `VALIDATION_FAILED` |
| 8.13 | missing `deviceCode` | ❌ 400 `VALIDATION_FAILED` |
| 8.14 | 601 readings | ❌ 400 `VALIDATION_FAILED`; nothing stored |
| 8.15 | `"measuredAt":"yesterday"` | ❌ 400 `MALFORMED_REQUEST` |

Per-reading rejection reasons (each in a batch with one good reading; the batch is still `200`, the bad one appears in `rejected` with the stated `reason`, and the good one is `accepted`):

| # | Bad reading | `reason` |
|---|---|---|
| 8.16 | `spo2: 100.01` or `39.99` | `SPO2_OUT_OF_RANGE` |
| 8.17 | `heartRate: 251` or `19` | `HEART_RATE_OUT_OF_RANGE` |
| 8.18 | `measuredAt` 60 s in the future | `MEASURED_AT_IN_FUTURE` |
| 8.19 | `measuredAt` long before the session started | `MEASURED_AT_BEFORE_SESSION` |
| 8.20 | `readingKey` missing, or contains a space | `INVALID_READING_KEY` |
| 8.21 | `measuredAt` missing | `MISSING_MEASURED_AT` |
| 8.22 | an element that is `null` | `READING_MISSING` |
| 8.23 | `signalQuality: 80` (any non-null) | `SIGNAL_QUALITY_NOT_SUPPORTED` |

Accepted edge cases (all in `accepted`): `spo2` 40 and 100, `heartRate` 20 and 250, `measuredAt` up to 5 s in the
future, the same `readingKey` in one batch twice (counts as 1 accepted + 1 duplicate), `spo2: 96.456` stored as
`96.46`.

## 9. Reading history and latest values (`/sessions/{id}/readings`, ADMIN + OPERATOR)

| # | Method + URL | Expected |
|---|---|---|
| 9.1 | `GET /sessions/{{sessionId}}/readings?size=100` | ✅ 200; `totalElements`=3; ordered by `measuredAt` **ascending**; the dropout reading has `spo2: null` and `heartRate: null` (**never 0**); `signalQuality: null`; both `measuredAt` and `receivedAt` present and different |
| 9.2 | `...?from=<t2>&to=<t3>` (from inclusive, to exclusive) | ✅ 200, only readings in the window |
| 9.3 | `...?size=1&page=1` | ✅ 200, one item, `totalPages`=3 |
| 9.4 | `...?size=1001` | ❌ 400 `VALIDATION_FAILED` |
| 9.5 | `...?from=<t>&to=<t>` (equal) | ❌ 400 `INVALID_REQUEST` |
| 9.6 | `GET /sessions/00000000-0000-0000-0000-000000000001/readings` | ❌ 404 |
| 9.7 | `GET /sessions/{{sessionId}}/readings/latest` | ✅ 200; `sessionStatus=ACTIVE`, `sourceType=SIMULATED`, `connectivityStatus=CONNECTED`, `deviceLastSeenAt` set; `latest` = the dropout row (null values); `lastSpo2.value`=95.5 and `lastHeartRate.value`=70 with their `measuredAt` |
| 9.8 | `GET /patients?q={{patientCode}}` | ✅ the row has `currentDevice.connectivityStatus=CONNECTED`, `activeSession`, and `latestReading{spo2,heartRate,measuredAt}` |
| 9.9 | `GET /devices/{{deviceId}}` | ✅ `connectivityStatus=CONNECTED`, `lastSeenAt` set |
| 9.10 | Latest for a session with **no** readings | ✅ 200; `latest`, `lastSpo2`, `lastHeartRate` are all `null`, `connectivityStatus=UNKNOWN` (never 0) |
| 9.11 | After ending the session (7.15): `GET .../readings` | ✅ still returns the 3 readings; `GET /patients/{{patientId}}` → `latestReading` is `null` (no active session) |
| 9.12 | Not logged in: `GET .../readings` | ❌ 401 |

## 10. Cross-cutting checks (run against any endpoint)

| # | Check | Expected |
|---|---|---|
| 10.1 | Any `POST/PUT/PATCH` as logged-in user **without** `X-XSRF-TOKEN` | ❌ 403 `CSRF_TOKEN_INVALID` |
| 10.2 | Any protected GET while logged out | ❌ 401 `UNAUTHORIZED`, JSON body, **no** `WWW-Authenticate` header |
| 10.3 | Wrong HTTP method, e.g. `DELETE /patients/{id}` | ❌ 405 `METHOD_NOT_ALLOWED` |
| 10.4 | Wrong content type (`text/plain`) on a POST | ❌ 415 `UNSUPPORTED_MEDIA_TYPE` |
| 10.5 | Send header `X-Correlation-Id: pm-test-1` | response header echoes `pm-test-1`, and `correlationId` in any error body is `pm-test-1` |
| 10.6 | Send an unsafe `X-Correlation-Id` (spaces / >64 chars) | response header is a generated UUID instead |
| 10.7 | No response anywhere contains a stack trace, SQL, or Java class names | confirm on at least 3 error responses |
| 10.8 | CORS preflight: `OPTIONS /auth/login` with `Origin: http://localhost:5173`, `Access-Control-Request-Method: POST` | ✅ 200, `Access-Control-Allow-Origin: http://localhost:5173`, `Access-Control-Allow-Credentials: true` |
| 10.9 | Same preflight with `Origin: https://evil.example` | ❌ 403, no `Access-Control-Allow-Origin` header |
| 10.10 | Timestamps in all responses end with `Z` (UTC) | confirm on patient, session and reading responses |

## 11. Session expiry / revocation (optional but valuable)

| # | Steps | Expected |
|---|---|---|
| 11.1 | Log in as the operator in cookie jar A. In jar B (admin) `POST /users/{operatorId}/deactivate` | The next call in jar A returns ❌ 401 `SESSION_INVALID` |
| 11.2 | Reactivate; log in again as the operator in jar A; as admin `PATCH /users/{operatorId}/role` `{"role":"ADMIN"}` | The next call in jar A returns ❌ 401 `SESSION_INVALID` (log in again to get the new role). **Set the role back to `OPERATOR`** afterwards |
| 11.3 | As admin `POST /users/{operatorId}/reset-password` while the operator is logged in | The operator's next call returns ❌ 401 `SESSION_INVALID` |

## 12. Wrap-up (leave the system tidy)

| # | Request | Expected |
|---|---|---|
| 12.1 | `POST /sessions/{{sessionId}}/end` if still ACTIVE | ✅ 200 |
| 12.2 | `POST /device-assignments/{{assignmentId}}/release` for every assignment created | ✅ 200 |
| 12.3 | `POST /auth/logout` (with CSRF) | ✅ 204 |
| 12.4 | `GET /auth/me` | ❌ 401 |
| 12.5 | `POST /auth/logout` **without** CSRF while logged in | ❌ 403 `CSRF_TOKEN_INVALID` |
| 12.6 | Delete the `operatorPassword` and any other temporary secret variables | – |

Records are never deleted through the API (by design); the test run leaves clearly-named synthetic records.

---

## Coverage checklist (every endpoint)

| Area | Endpoints | Sections |
|---|---|---|
| Health | `GET /health`, `GET /actuator/health` | 1 |
| Auth | `GET /auth/csrf`, `POST /auth/login`, `GET /auth/me`, `POST /auth/logout`, `POST /auth/password` | 2, 12 |
| Users | `GET /users`, `GET /users/{id}`, `POST /users`, `PATCH /users/{id}/role`, `POST /users/{id}/deactivate`, `POST /users/{id}/reactivate`, `POST /users/{id}/reset-password` | 3, 11 |
| Patients | `GET /patients`, `GET /patients/{id}`, `POST /patients`, `PUT /patients/{id}`, `PUT /patients/{id}/contact`, `PUT /patients/{id}/medical-details`, `PATCH /patients/{id}/status` | 4 |
| Devices | `GET /devices`, `GET /devices/{id}`, `POST /devices`, `PUT /devices/{id}`, `PATCH /devices/{id}/enrollment`, `POST /devices/{id}/deactivate`, `POST /devices/{id}/reactivate` | 5 |
| Assignments | `POST /device-assignments`, `POST /device-assignments/{id}/release`, `GET /device-assignments/{id}`, `GET /device-assignments` | 6 |
| Sessions | `POST /sessions`, `POST /sessions/{id}/end`, `POST /sessions/{id}/cancel`, `GET /sessions/{id}`, `GET /sessions` | 7 |
| Ingestion | `POST /ingestion/readings` | 8 |
| Readings | `GET /sessions/{id}/readings`, `GET /sessions/{id}/readings/latest` | 9 |

**Total: 36 endpoints (method + path).** Not built yet, so **do not test**: predictions/AI results, alerts/event
review, simulator control, live push (SSE/WebSocket). They return 404/401 and are tracked in `docs/api/README.md`.

## Expected final report from the agent

For each numbered row: **PASS / FAIL**, the actual status code, the actual `code` (for errors) and, for FAIL,
the response body (with secrets removed) and the `correlationId`. Finish with totals and the list of failed rows.
