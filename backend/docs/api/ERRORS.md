# SomniSense API — Response and Error Contract

**Status:** Implemented and tested (Stage 2, 2026-11-21). Frontend may rely on this.
**Applies to:** every Spring Boot endpoint under `/api/v1`.

## Success responses

Plain JSON: the response body is the data itself, with no wrapper. The HTTP status signals success
(`200 OK`, `201 Created`, `204 No Content`).

```json
{ "id": "8f1c...", "patientCode": "PT-000123" }
```

## Error responses

Every error, including 401 and 403 from security, has exactly this shape:

```json
{
  "timestamp": "2026-11-21T12:45:47.530Z",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_FAILED",
  "message": "Request has invalid fields",
  "path": "/api/v1/patients",
  "correlationId": "3c9e1a2b-...",
  "fieldErrors": [
    { "field": "fullName", "message": "must not be blank" }
  ]
}
```

| Field | Type | Notes |
|---|---|---|
| `timestamp` | string | ISO-8601, always UTC (`Z`). |
| `status` | number | Same as the HTTP status. |
| `error` | string | HTTP reason phrase. |
| `code` | string | **Stable. Branch on this, never on `message`.** |
| `message` | string | Human-readable and safe to show. Never contains SQL, stack traces or secrets. |
| `path` | string | Request path, without the query string. |
| `correlationId` | string | Same value as the `X-Correlation-Id` response header. Quote it in bug reports. |
| `fieldErrors` | array | Always present. Filled only for `VALIDATION_FAILED`. Rejected values are never echoed back. |

## Error codes

| `code` | HTTP | When |
|---|---|---|
| `VALIDATION_FAILED` | 400 | One or more body/query fields are invalid. See `fieldErrors`. |
| `MALFORMED_REQUEST` | 400 | Body missing, not valid JSON, or a value of the wrong type. |
| `INVALID_REQUEST` | 400 | A business rule rejected the request, or a query parameter has the wrong type. |
| `SELF_ACTION_NOT_ALLOWED` | 400 | An Admin tried to deactivate, demote or reset their own account. |
| `CURRENT_PASSWORD_INCORRECT` | 400 | Change-password: the current password was wrong. Do **not** log the user out. |
| `UNAUTHORIZED` | 401 | Not logged in, or the session has expired. Go to the login page. |
| `INVALID_CREDENTIALS` | 401 | Login failed. One message for every cause. |
| `SESSION_INVALID` | 401 | The account was deactivated or changed since login. Log in again. |
| `FORBIDDEN` | 403 | Logged in but the role is not allowed. Show the access-denied page. |
| `CSRF_TOKEN_INVALID` | 403 | Missing or stale CSRF token. Call `GET /auth/csrf` and retry. |
| `RESOURCE_NOT_FOUND` | 404 | The record or URL does not exist. |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method for the URL. |
| `CONFLICT` | 409 | Clashes with the current state, e.g. the device already has an active assignment. |
| `LAST_ACTIVE_ADMIN` | 409 | The change would leave no active Admin. |
| `SOURCE_TYPE_MISMATCH` | 400 | Assignment: the device type is not the same as the patient's source type. |
| `DEVICE_NOT_AVAILABLE` | 409 | Assignment: the device is inactive or not `ENROLLED`. |
| `PATIENT_NOT_ACTIVE` | 409 | Assignment: the patient is `INACTIVE`. |
| `DEVICE_ALREADY_ASSIGNED` | 409 | The device is with another patient. Confirm, then resend with `replaceExisting=true`. |
| `PATIENT_ALREADY_HAS_DEVICE` | 409 | The patient has another device. Confirm, then resend with `replaceExisting=true`. |
| `DEVICE_IN_USE` | 409 | Deactivate device: release its assignment first. |
| `PATIENT_HAS_ACTIVE_DEVICE` | 409 | Set patient INACTIVE: release their device first. |
| `NO_ACTIVE_ASSIGNMENT` | 409 | Start session: the patient has no device assigned. |
| `ASSIGNMENT_MISMATCH` | 409 | Start session: the `deviceAssignmentId` sent is not the current one. Reload. |
| `SESSION_ALREADY_ACTIVE` | 409 | Start session: another session is already active. |
| `SESSION_ACTIVE` | 409 | Release or reassign: end the monitoring session first. |
| `INVALID_SESSION_TRANSITION` | 409 | End or cancel: the session is already in the other final state. |
| `NO_ACTIVE_SESSION` | 409 | Ingestion: the device has no ACTIVE monitoring session, so nothing was stored. |
| `SESSION_MISMATCH` | 409 | Ingestion: `sessionCode` is not the device's active session. |

Ingestion problems with **individual readings** do not produce errors. They are listed in the `200` response's
`rejected` array (see [INGESTION.md](INGESTION.md)).
| `DATA_INTEGRITY_VIOLATION` | 409 | The database rejected the change, e.g. a duplicate value. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Send `Content-Type: application/json`. |
| `INTERNAL_ERROR` | 500 | Unexpected server error. Retry later and report the `correlationId`. |

## Correlation ID

- Every response has an `X-Correlation-Id` header.
- Clients may send their own `X-Correlation-Id` (1–64 characters, `A-Z a-z 0-9 -`). Otherwise the server generates a UUID.
- Server logs show `[cid:<id>]` on each line, so one request can be traced end to end.
