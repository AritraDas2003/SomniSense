# SomniSense API — Reading Ingestion (machine-to-machine)

**Status:** Implemented and tested (Stage 9, 2026-11-21). This API is for the **simulator or trusted
gateway only**. The React frontend never calls it; staff read readings through [READINGS.md](READINGS.md).
Errors follow [ERRORS.md](ERRORS.md).

## Authentication

```
X-Ingestion-Key: <value of INGESTION_API_KEY>
```

- The key is one shared secret, at least 32 characters, kept in the server's environment or `.env`.
  Never commit it and never put it in the browser.
- A missing or wrong key returns `401 UNAUTHORIZED`.
- **Login cookies are ignored on this path:** a logged-in staff browser cannot submit readings.
- No session is created, and no CSRF token or CORS rules apply, because this is a separate, stateless security chain.
- The key proves that the sender knows the shared secret. It does **not** prove which physical device sent the data.

## Endpoint

`POST /api/v1/ingestion/readings`

```json
{
  "deviceCode": "DEV-H2FP2ABH",
  "sessionCode": "SES-20261121-68KSW8",
  "readings": [
    { "readingKey": "DEV-H2FP2ABH-000123", "measuredAt": "2026-11-21T14:05:00Z", "spo2": 96.0, "heartRate": 67.0, "signalQuality": null },
    { "readingKey": "DEV-H2FP2ABH-000124", "measuredAt": "2026-11-21T14:05:01Z", "spo2": null, "heartRate": null }
  ]
}
```

| Field | Rules |
|---|---|
| `deviceCode` | required, case-insensitive |
| `sessionCode` | optional; if sent, it must match the device's active session (`409 SESSION_MISMATCH` otherwise) |
| `readings` | 1–600 items (10 minutes at 1 Hz) |

### How a batch is routed (whole-batch errors; nothing is stored)

The backend finds the device by `deviceCode`, then its active assignment, then that assignment's
**ACTIVE** monitoring session. The sender can never choose the patient or the session.

| Problem | Response |
|---|---|
| Unknown `deviceCode` | `404 RESOURCE_NOT_FOUND` |
| The device is inactive | `409 DEVICE_NOT_AVAILABLE` |
| No assignment, or no ACTIVE session (not started, already ended or cancelled) | `409 NO_ACTIVE_SESSION` |
| `sessionCode` does not match | `409 SESSION_MISMATCH` |
| Missing `deviceCode`, empty `readings`, or more than 600 readings | `400 VALIDATION_FAILED` |
| Body is not valid JSON, or a value has the wrong type (for example `"measuredAt":"yesterday"`) | `400 MALFORMED_REQUEST` |

### Rules for each reading (partial acceptance)

A reading that breaks a rule is **rejected alone**; the valid readings in the same batch are still stored.

| Rule | `reason` if broken |
|---|---|
| The item is not `null` | `READING_MISSING` |
| `readingKey` is required: 1–100 characters of `A-Z a-z 0-9 . _ : -`. It must be unique per device, so use a sequence such as `DEV-X-000123` | `INVALID_READING_KEY` |
| `measuredAt` is required (ISO-8601 UTC) | `MISSING_MEASURED_AT` |
| `measuredAt` is at most **10 s ahead** of the server clock | `MEASURED_AT_IN_FUTURE` |
| `measuredAt` is not more than 10 s before the session started | `MEASURED_AT_BEFORE_SESSION` |
| `spo2` is `null` or 40–100 | `SPO2_OUT_OF_RANGE` |
| `heartRate` is `null` or 20–250 | `HEART_RATE_OUT_OF_RANGE` |
| `signalQuality` is `null`. Its meaning is not defined yet (decision D-07) | `SIGNAL_QUALITY_NOT_SUPPORTED` |

How values are handled:

- **Out-of-range values are never clamped**: the reading is rejected and nothing is stored for it.
- The ranges are software input guards, not clinical limits.
- `null` means "no value" (for example a sensor dropout) and is stored as `NULL`, **never as 0**.
- Values are rounded to 2 decimals, the precision of the database column.
- Timestamps are stored to the microsecond.
- Readings may arrive out of time order; queries always sort by `measuredAt`.

### Duplicates and retries

Each device can store a given `readingKey` only once (V4 unique index). Sending the same reading again is
**safe**: it is skipped and counted under `duplicates`. That stays true even if two copies of a batch arrive
at the same moment. **If you are unsure whether a batch arrived, resend the whole batch.**

### Response: `200`

```json
{
  "deviceCode": "DEV-H2FP2ABH",
  "sessionId": "3cb7635e-...",
  "sessionCode": "SES-20261121-68KSW8",
  "receivedAt": "2026-11-21T14:13:19.692594Z",
  "accepted": 2,
  "duplicates": 0,
  "rejected": [
    { "index": 2, "readingKey": "live-003", "reason": "SPO2_OUT_OF_RANGE", "message": "spo2 must be between 40 and 100" }
  ]
}
```

- `accepted`: readings newly stored.
- `duplicates`: readings already stored, or repeated within this batch.
- `rejected`: each rejected reading's position in the `readings` array (`index`, starting at 0) and its reason.

### Side effects

- If at least one reading was accepted or was a duplicate, the device's `lastSeenAt` is set to the server's
  receipt time. That drives `CONNECTED` / `STALE` / `DISCONNECTED`.
- A batch in which every reading was rejected does **not** mark the device as seen.
- `measuredAt` (device clock) and `receivedAt` (server clock) are stored separately.
- Ingestion is high-volume, so it is not written to `audit_logs`; batch counts are logged at debug level.

## curl example

```bash
curl -X POST http://localhost:8080/api/v1/ingestion/readings \
  -H "Content-Type: application/json" -H "X-Ingestion-Key: $INGESTION_API_KEY" \
  -d '{"deviceCode":"DEV-H2FP2ABH","readings":[{"readingKey":"r-1","measuredAt":"2026-11-21T14:05:00Z","spo2":96,"heartRate":67}]}'
```

## Not included yet

The Python simulator does not send to this endpoint yet. Simulator orchestration (Stage 13) needs the AI
lead's confirmed contract first. Until then, this endpoint can be driven by curl, Postman or a small script.
