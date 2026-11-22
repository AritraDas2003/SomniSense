# SomniSense API — Reading History and Latest Values (staff)

**Status:** Implemented and tested (Stage 9, 2026-11-21). The frontend may replace its readings and chart
mocks. Session cookie as in [AUTH.md](AUTH.md). Errors follow [ERRORS.md](ERRORS.md).

**Roles:** `ADMIN` and `OPERATOR`. Readings get **into** the system through the machine-only
[INGESTION.md](INGESTION.md).

## Endpoints

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/api/v1/sessions/{sessionId}/readings?from=&to=&page=0&size=300` | `200` Page of Reading, **oldest first** | `400` (`from` not before `to`, or `size` > 1000), `404` (unknown session) |
| GET | `/api/v1/sessions/{sessionId}/readings/latest` | `200` LatestReadings | `404` |

History parameters:

- `from` (inclusive) and `to` (exclusive) filter on `measuredAt`. They are ISO-8601 UTC and optional.
- `size` defaults to 300 and is at most 1000. History is **always paged**, never unbounded.

Readings stay available after the session is ENDED or CANCELLED. The session's own `status` tells you which.

### Reading

```json
{
  "id": "...",
  "readingKey": "live-002",
  "measuredAt": "2026-11-21T14:13:17Z",
  "receivedAt": "2026-11-21T14:13:19.692594Z",
  "spo2": null,
  "heartRate": null,
  "signalQuality": null
}
```

- `measuredAt` is the device clock; `receivedAt` is the server clock.
- `spo2` / `heartRate` are `null` when the value was missing (a dropout).
- **Draw a gap in the chart, never 0** (UI plan C51).
- `signalQuality` is always `null` for now (decision D-07).

### LatestReadings: everything the live cards need (UI plan C44–C46)

```json
{
  "sessionId": "...",
  "sessionStatus": "ACTIVE",
  "sourceType": "SIMULATED",
  "connectivityStatus": "CONNECTED",
  "deviceLastSeenAt": "2026-11-21T14:13:19.891594Z",
  "latest":        { "readingKey": "live-002", "measuredAt": "...", "spo2": null, "heartRate": null, "...": "..." },
  "lastSpo2":      { "value": 97.00, "measuredAt": "2026-11-21T14:13:16Z" },
  "lastHeartRate": { "value": 68.00, "measuredAt": "2026-11-21T14:13:16Z" }
}
```

| Field | Meaning | UI behaviour |
|---|---|---|
| `latest` | the newest reading row; its values may be `null` (dropout right now) | — |
| `lastSpo2` / `lastHeartRate` | the newest **non-null** value of each metric, with its time | keep showing it, marked **stale** if old (UI plan: "retain last value but clearly mark it as stale") |
| all three `null` | no readings yet | "No readings yet", **never 0 % or 0 bpm** |
| `connectivityStatus` | `CONNECTED` / `STALE` / `DISCONNECTED` / `UNKNOWN` from `deviceLastSeenAt` | `CONNECTED` means data is arriving, not that the patient is fine |
| `sourceType` | `SIMULATED` or `PHYSICAL` | show the **SIMULATED** badge |

Poll this endpoint for live updates for now. SSE or WebSocket live updates come later (Stage 14).

## On the patient list and detail

`GET /patients` rows and `GET /patients/{id}` include `latestReading`: the newest reading of the patient's
**ACTIVE** session, `{ "spo2", "heartRate", "measuredAt" }`, or `null` when no session is active or there
are no readings. Together with `currentDevice.connectivityStatus` this fills the dashboard columns
"latest SpO₂/HR" and "last reading time" (UI plan C15 and C23).
