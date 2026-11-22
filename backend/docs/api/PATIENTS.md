# SomniSense API — Patients

**Status:** Implemented and tested (Stage 5, 2026-11-21). The frontend may replace its patient list and
Add Patient mocks. Session cookie + CSRF header as in [AUTH.md](AUTH.md). Errors follow [ERRORS.md](ERRORS.md).

**Roles:** `ADMIN` and `OPERATOR` can use everything here, **except** `PATCH /status`, which is `ADMIN` only.
Patients are never deleted.

## Endpoints

| Method | Path | Body | Success | Typical errors |
|---|---|---|---|---|
| GET | `/api/v1/patients` | — | `200` Page of PatientSummary | `400` (bad filter, sort or page) |
| GET | `/api/v1/patients/{id}` | — | `200` PatientDetail | `404 RESOURCE_NOT_FOUND` |
| POST | `/api/v1/patients` | CreatePatient | `201` PatientDetail + `Location` | `400 VALIDATION_FAILED`, `400 MALFORMED_REQUEST` (unknown enum value), `400 INVALID_REQUEST` (birth date before 1900) |
| PUT | `/api/v1/patients/{id}` | Profile | `200` PatientDetail | `400`, `404` |
| PUT | `/api/v1/patients/{id}/contact` | Contact | `200` PatientDetail | `400`, `404` |
| PUT | `/api/v1/patients/{id}/medical-details` | MedicalDetails | `200` PatientDetail | `400`, `404` |
| PATCH | `/api/v1/patients/{id}/status` | `{"status":"ACTIVE"\|"INACTIVE"}` | `200` PatientDetail | `403 FORBIDDEN` (Operator), `404` |

**About PUT:** every PUT **replaces** the whole block. A field you leave out, or send blank, is cleared.
`patientCode` and `sourceType` can never be changed; if a request includes them, they are ignored.

### List parameters

| Param | Default | Rules |
|---|---|---|
| `sourceType` | — | `PHYSICAL` or `SIMULATED` |
| `status` | — | `ACTIVE` or `INACTIVE` |
| `q` | — | case-insensitive "contains" on patient code, full name or alias; max 100 characters |
| `page` / `size` | `0` / `20` | `size` 1–100 |
| `sort` | `createdAt` | `patientCode`, `fullName` or `createdAt` |
| `direction` | `desc` | `asc` or `desc` |

**Since Stage 7** every list row and the detail view include `currentDevice`:
`{ "assignmentId", "deviceId", "deviceCode", "connectivityStatus", "lastSeenAt", "assignedAt" }`, or `null`
when no device is assigned. See [ASSIGNMENTS.md](ASSIGNMENTS.md).
**Since Stage 8** they also include `activeSession`: `{ "sessionId", "sessionCode", "startedAt" }`, or
`null` (see [SESSIONS.md](SESSIONS.md)).
**Since Stage 9** they also include `latestReading`: `{ "spo2", "heartRate", "measuredAt" }` from the active
session, or `null`. Values may be `null` (dropout); never show them as 0 (see [READINGS.md](READINGS.md)).

**Setting a patient INACTIVE while a device is assigned returns `409 PATIENT_HAS_ACTIVE_DEVICE`.**
Release the device first.

## Shapes

**CreatePatient**: profile fields plus `sourceType` and the optional blocks.

```json
{
  "fullName": "Synthetic Patient 01",
  "alias": "SP-01",
  "dateOfBirth": "1980-05-17",
  "gender": "FEMALE",
  "sourceType": "SIMULATED",
  "contact": { "phone": "+91 98300 12345", "city": "Kolkata" },
  "medicalDetails": { "sleepDisorder": "Suspected OSA", "notes": "..." }
}
```

| Field | Required | Rules |
|---|---|---|
| `fullName` | yes | max 150 characters |
| `alias` | no | max 100 characters |
| `dateOfBirth` | no | `YYYY-MM-DD`, in the past, not before 1900-01-01 |
| `gender` | no | `FEMALE`, `MALE`, `OTHER` or `UNDISCLOSED` |
| `sourceType` | yes, create only | `PHYSICAL` or `SIMULATED`; fixed after creation |
| `contact` | no | see Contact |
| `medicalDetails` | no | see MedicalDetails |

There is **no `patientCode` input**. The backend generates codes like `PT-L6NP4WLM`: `PT-` plus 8 characters
from `2-9 A-Z`, never using `0`, `1`, `I` or `O`.

**Profile** (`PUT /patients/{id}`) takes the same fields as CreatePatient without `sourceType`, `contact`
and `medicalDetails`.

**Contact** (every field optional):

| Field | Max length | Notes |
|---|---|---|
| `phone` | 25 | only digits, spaces and `+ ( ) - .` |
| `email` | 255 | valid email; stored lower-case |
| `addressLine` | 500 | |
| `city` | 100 | |
| `state` | 100 | |
| `postalCode` | 15 | |
| `emergencyContactName` | 150 | |
| `emergencyContactPhone` | 25 | same rule as `phone` |
| `emergencyContactRelationship` | 50 | |

**MedicalDetails** (every field optional, free text that the system never interprets):

| Field | Max length |
|---|---|
| `medicalHistory` | 5000 |
| `sleepDisorder` | 150 |
| `medications` | 5000 |
| `notes` | 5000 |

The backend records who last edited these fields (`updatedBy`) and when (`updatedAt`).

**PatientSummary** (one list row). It deliberately has **no** date of birth, contact or medical data.

```json
{
  "id": "b0b96c65-20de-4d74-a907-54b68c85c3fe",
  "patientCode": "PT-L6NP4WLM",
  "fullName": "Demo Simulated Patient",
  "alias": "DEMO-SIM-01",
  "sourceType": "SIMULATED",
  "status": "ACTIVE",
  "createdAt": "2026-11-21T13:21:24.764872Z"
}
```

**PatientDetail**:

```json
{
  "id": "...",
  "patientCode": "PT-L6NP4WLM",
  "fullName": "...",
  "alias": "...",
  "dateOfBirth": null,
  "gender": "UNDISCLOSED",
  "sourceType": "SIMULATED",
  "status": "ACTIVE",
  "createdBy": "<staff user id>",
  "createdAt": "...",
  "updatedAt": "...",
  "contact": null,
  "medicalDetails": {
    "medicalHistory": null,
    "sleepDisorder": null,
    "medications": null,
    "notes": "...",
    "updatedBy": "<staff user id>",
    "updatedAt": "..."
  }
}
```

- `contact` and `medicalDetails` are `null` until they are first entered.
- Blank text is always returned as `null`.
- Timestamps are UTC (ISO-8601). `dateOfBirth` is a plain date with no time zone.

## UI notes

- Show a persistent **SIMULATED** label wherever `sourceType` is `SIMULATED`.
- After create, show the generated `patientCode` (UI plan C29).
- Ask for confirmation before setting a patient to `INACTIVE`.

## Audit entries

`user_id` is the staff member who acted; `entity_type` = `PATIENT`. Audit details **never** contain names,
dates of birth, contact details or medical text.

| Action | `details` |
|---|---|
| `PATIENT_CREATED` | `{"sourceType":"SIMULATED","withContact":false,"withMedicalDetails":true}` |
| `PATIENT_UPDATED` | `{"fields":["fullName","gender"]}` (field names only) |
| `PATIENT_CONTACT_UPDATED` | — |
| `PATIENT_MEDICAL_DETAILS_UPDATED` | — |
| `PATIENT_STATUS_CHANGED` | `{"from":"ACTIVE","to":"INACTIVE"}` |
