# SomniSense API — Device Assignments

**Status:** Implemented and tested (Stage 7, 2026-11-21). The frontend may replace its device-assignment mocks.
Session cookie + CSRF header as in [AUTH.md](AUTH.md). Errors follow [ERRORS.md](ERRORS.md).

**Roles:** `ADMIN` and `OPERATOR`.

An assignment means "device X is with patient Y". It is **active** until it is released. History is never
overwritten: releasing fills in `releasedBy` and `releasedAt`, and every reassignment creates a new row.

## Rules (enforced by the backend)

| Rule | Error if broken |
|---|---|
| The device must be **active** and **ENROLLED** | `409 DEVICE_NOT_AVAILABLE` |
| The patient must be **ACTIVE** | `409 PATIENT_NOT_ACTIVE` |
| The device type must equal the patient's source type (SIMULATED↔SIMULATED, PHYSICAL↔PHYSICAL) | `400 SOURCE_TYPE_MISMATCH` |
| A device can have only **one** active assignment | `409 DEVICE_ALREADY_ASSIGNED` |
| A patient can have only **one** active device | `409 PATIENT_ALREADY_HAS_DEVICE` |
| An assigned device cannot be deactivated (release it first) | `409 DEVICE_IN_USE` (on `POST /devices/{id}/deactivate`) |
| A patient with an assigned device cannot be set INACTIVE (release it first) | `409 PATIENT_HAS_ACTIVE_DEVICE` (on `PATCH /patients/{id}/status`) |
| Releasing or reassigning while a monitoring session is ACTIVE on the assignment is refused (end the session first) | `409 SESSION_ACTIVE` |

**Simultaneous requests:** the device and patient rows are locked during an assignment. In addition, the
database itself (V2 partial unique indexes) refuses a second active row. If two people assign at the same
moment, exactly one succeeds and the other gets one of the 409 codes above. Neither ever gets a 500.

## Endpoints

| Method | Path | Body | Success | Typical errors |
|---|---|---|---|---|
| POST | `/api/v1/device-assignments` | AssignDevice | `201` Assignment + `Location` (new), **or** `200` (this exact pairing already existed) | see the rules above, `400 VALIDATION_FAILED`, `404` (unknown device or patient) |
| POST | `/api/v1/device-assignments/{id}/release` | — | `200` Assignment (`active:false`); repeating it is safe | `404` |
| GET | `/api/v1/device-assignments/{id}` | — | `200` Assignment | `404` |
| GET | `/api/v1/device-assignments?deviceId=&patientId=&active=&page=&size=` | — | `200` Page of Assignment, newest first | `400` |

### AssignDevice

```json
{ "deviceId": "...", "patientId": "...", "replaceExisting": false }
```

`replaceExisting` (default `false`) controls what happens when the device or the patient already has an
active assignment:

- **`false`**: the request returns `409` with a message that names what is in the way, for example
  *"Device DEV-H2FP2ABH is already assigned to patient PT-L6NP4WLM. Send replaceExisting=true to reassign it."*
  Show it in the reassignment confirmation dialog (UI plan C37).
- **`true`** (after the user confirms): the old assignment(s) are released and the new one is created in
  **one transaction**, so either everything happens or nothing does. If both the device and the patient were
  busy, both old assignments are released.

### Assignment

```json
{
  "id": "8cd226a5-1780-4b88-96ca-92dec23d58f1",
  "active": true,
  "deviceId": "...",
  "deviceCode": "DEV-H2FP2ABH",
  "deviceType": "SIMULATED",
  "patientId": "...",
  "patientCode": "PT-L6NP4WLM",
  "patientFullName": "Demo Simulated Patient",
  "assignedBy": "<staff user id>",
  "assignedAt": "2026-11-21T13:48:40.254602Z",
  "releasedBy": null,
  "releasedAt": null
}
```

## Where assignments also appear

- `GET /devices`, `GET /devices/{id}` → `currentAssignment`:
  `{ "assignmentId", "patientId", "patientCode", "assignedAt" }`, or `null`.
- `GET /patients` (each row) and `GET /patients/{id}` → `currentDevice`:
  `{ "assignmentId", "deviceId", "deviceCode", "connectivityStatus", "lastSeenAt", "assignedAt" }`, or `null`.
  This gives the dashboard's device column together with live freshness (UI plan C15 and C23).

## Audit entries

`entity_type` = `ASSIGNMENT`; `user_id` = the staff member who acted.

| Action | `details` |
|---|---|
| `DEVICE_ASSIGNED` | `{"deviceId":"...","patientId":"...","replaced":0\|1\|2}` |
| `DEVICE_RELEASED` | `{"reason":"RELEASED"}` (manual release) or `{"reason":"REASSIGNED"}` (released by a replacing assignment) |
