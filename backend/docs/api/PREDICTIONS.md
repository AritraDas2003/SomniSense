# SomniSense API — Session Predictions (staff)

**Status:** Persistence, retrieval endpoints, and internal Model C FastAPI sidecar client implemented and verified (Stage 3, 2026-11-26; 181/181 Maven tests + 7/7 Python AI sidecar tests passing). Automated window scheduling arrives in Stage 4. Session cookie as in [AUTH.md](AUTH.md). Errors follow [ERRORS.md](ERRORS.md).

**Roles:** `ADMIN` and `OPERATOR`. Prediction records are produced internally by Spring Boot inference orchestration and are never submitted directly by browser clients.

> **Clinical safety notice:** Model outputs are review prompts ("Possible event — review"), never a diagnosis. Missing or rejected windows never produce fabricated `NORMAL` predictions.

---

## Endpoints

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/api/v1/sessions/{sessionId}/predictions?from=&to=&page=0&size=20` | `200` Page of `PredictionResponse`, **newest first** | `400` (`from` not before `to`, invalid `page`/`size`, malformed UUID/timestamp), `401`, `403`, `404` (unknown session) |
| GET | `/api/v1/sessions/{sessionId}/predictions/latest` | `200` `LatestPredictionResponse` | `400` (malformed UUID), `401`, `403`, `404` (unknown session) |

### History parameters

- `from` (optional, ISO-8601 UTC): inclusive lower bound on `windowEnd` (`windowEnd >= from`).
- `to` (optional, ISO-8601 UTC): exclusive upper bound on `windowEnd` (`windowEnd < to`).
- When both `from` and `to` are supplied, `from` must be strictly before `to` (`400 INVALID_REQUEST` otherwise).
- `page` (optional, default `0`, minimum `0`).
- `size` (optional, default `20`, bounded `1..100`).
- **Deterministic sorting:** `windowEnd DESC`, `processedAt DESC`, `id ASC`.
- **Session scoping:** Every query verifies the requested `sessionId` exists and returns only predictions belonging to that session. Predictions remain readable after a session is `ENDED` or `CANCELLED`.

---

## Response Schemas

### `PredictionResponse`

```json
{
  "id": "b4b41e5f-31c2-4fa7-9b63-6b0b6cb4b420",
  "sessionId": "3cb7635e-e802-40c3-87dd-0f86c511cbd3",
  "modelVersion": "phase7_model_c:1.0.0",
  "windowStart": "2026-11-26T00:00:00Z",
  "windowEnd": "2026-11-26T00:00:30Z",
  "predictedClass": "APNEA_EVENT",
  "confidenceScore": 0.874210,
  "predictionStatus": "NEEDS_REVIEW",
  "inputQualityStatus": "VALID",
  "processedAt": "2026-11-26T00:00:31.120Z",
  "processingTimeMs": 42,
  "errorMessage": null
}
```

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `id` | UUID string | No | Unique prediction ID |
| `sessionId` | UUID string | No | Owning monitoring session |
| `modelVersion` | string | No | Model identifier & version (e.g. `phase7_model_c:1.0.0`) |
| `windowStart` | ISO-8601 UTC | No | Inclusive window start |
| `windowEnd` | ISO-8601 UTC | No | Exclusive window end; indexed for time filtering and ordering |
| `predictedClass` | enum string | Yes | `APNEA_EVENT`, `NORMAL`, or `null` (always `null` for `REJECTED` / `ERROR`) |
| `confidenceScore` | number | Yes | Positive-class probability in `[0, 1]` (6 decimal places), or `null` for `REJECTED` / `ERROR` |
| `predictionStatus` | enum string | No | `COMPLETED`, `NEEDS_REVIEW`, `REJECTED`, `ERROR` |
| `inputQualityStatus` | enum string | Yes | `VALID` (for `COMPLETED`/`NEEDS_REVIEW`), `DEGRADED` or `REJECTED` (both mapped to `predictionStatus: "REJECTED"` with `predictedClass: null`, `confidenceScore: null`), or `null` |
| `processedAt` | ISO-8601 UTC | No | Server/inference completion timestamp |
| `processingTimeMs` | integer | Yes | Non-negative inference latency in milliseconds |
| `errorMessage` | string | Yes | Safe rejection or error description (no stack traces) |

### Paginated History (`GET /api/v1/sessions/{sessionId}/predictions`)

Uses the standard `PageResponse` envelope:

```json
{
  "content": [
    {
      "id": "b4b41e5f-31c2-4fa7-9b63-6b0b6cb4b420",
      "sessionId": "3cb7635e-e802-40c3-87dd-0f86c511cbd3",
      "modelVersion": "phase7_model_c:1.0.0",
      "windowStart": "2026-11-26T00:00:00Z",
      "windowEnd": "2026-11-26T00:00:30Z",
      "predictedClass": "APNEA_EVENT",
      "confidenceScore": 0.874210,
      "predictionStatus": "NEEDS_REVIEW",
      "inputQualityStatus": "VALID",
      "processedAt": "2026-11-26T00:00:31.120Z",
      "processingTimeMs": 42,
      "errorMessage": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### Latest Prediction (`GET /api/v1/sessions/{sessionId}/predictions/latest`)

**When at least one prediction exists (`200 OK`):**

```json
{
  "sessionId": "3cb7635e-e802-40c3-87dd-0f86c511cbd3",
  "sessionStatus": "ACTIVE",
  "hasPrediction": true,
  "latest": {
    "id": "b4b41e5f-31c2-4fa7-9b63-6b0b6cb4b420",
    "sessionId": "3cb7635e-e802-40c3-87dd-0f86c511cbd3",
    "modelVersion": "phase7_model_c:1.0.0",
    "windowStart": "2026-11-26T00:00:00Z",
    "windowEnd": "2026-11-26T00:00:30Z",
    "predictedClass": "APNEA_EVENT",
    "confidenceScore": 0.874210,
    "predictionStatus": "NEEDS_REVIEW",
    "inputQualityStatus": "VALID",
    "processedAt": "2026-11-26T00:00:31.120Z",
    "processingTimeMs": 42,
    "errorMessage": null
  }
}
```

**No-prediction-yet behaviour (`200 OK`):**

When the session exists but no predictions have been recorded yet, the endpoint returns `200 OK` with `hasPrediction: false` and `latest: null` (mirrors `/readings/latest` and avoids misinterpreting a missing prediction as `NORMAL` or a missing session `404`):

```json
{
  "sessionId": "3cb7635e-e802-40c3-87dd-0f86c511cbd3",
  "sessionStatus": "ACTIVE",
  "hasPrediction": false,
  "latest": null
}
```

---

## Error Cases

| Condition | HTTP | `code` |
|---|---|---|
| Not logged in or expired session | `401` | `UNAUTHORIZED` |
| Role other than `ADMIN` or `OPERATOR` | `403` | `FORBIDDEN` |
| Unknown `sessionId` | `404` | `RESOURCE_NOT_FOUND` |
| Malformed `sessionId` UUID or invalid `from`/`to` timestamp | `400` | `INVALID_REQUEST` |
| `from` not strictly before `to` | `400` | `INVALID_REQUEST` |
| `page < 0` or `size < 1` or `size > 100` | `400` | `VALIDATION_FAILED` |
