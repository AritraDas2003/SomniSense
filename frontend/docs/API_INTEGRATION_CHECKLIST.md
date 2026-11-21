# SomniSense frontend API integration checklist

The current UI uses deterministic mock services. Endpoint paths and DTOs below are **PROVISIONAL — CONFIRM WITH BACKEND** and are not approved API contracts.

## Mock mode

- Mock mode is enabled by default so the frontend runs without the Spring Boot service.
- Set `VITE_USE_MOCK_API=false` and `VITE_API_BASE_URL=http://localhost:8080/api/v1` to exercise the real HTTP service adapters.
- Vite variables are public browser configuration; do not put credentials or secrets in them.
- Mock data is held in memory and resets on a page reload. The mock signed-in user is stored only for the current browser session.
- Sensor fixtures are fixed demo values with timestamps relative to page load. They are not patient measurements, live telemetry, or a clinical probability.

## Provisional service contracts

| Frontend function | Purpose and temporary shape | Provisional endpoint | Status / questions |
| --- | --- | --- | --- |
| `login({ username, password })` | Returns `{ id, name, username, role, isMock }`; invalid credentials return an error with status `401`. | `POST /auth/login` | **PROVISIONAL — CONFIRM WITH BACKEND.** Confirm auth/session mechanism, request fields, role DTO, session expiry, and logout behavior. |
| `getDashboard()` | Returns `{ summary: { patientCount, activeSessions, itemsNeedingReview, dataIssues }, patients, dataMode }`. | `GET /dashboard/summary` | **PROVISIONAL — CONFIRM WITH BACKEND.** Confirm count definitions, freshness thresholds, and which aggregate fields are supported. |
| `getPatients()` | Returns patient view models with code, display name, source, device/session, latest available readings, freshness, and review status. | `GET /patients` | **PROVISIONAL — CONFIRM WITH BACKEND.** Confirm pagination/filtering, DTO mapping, and latest-reading semantics. |
| `createPatient(input)` | Request `{ fullName, alias?, sourceType }`; returns the created record with a server-generated `patientCode`. Mock duplicate name returns `409`. | `POST /patients` | **PROVISIONAL — CONFIRM WITH BACKEND.** Confirm accepted fields, uniqueness rules, validation error format, and whether source is selected at creation. |
| `getDevices()` | Returns inventory records with type, status, enrollment, assignment summary, and last-seen timestamp. | `GET /devices` | **PROVISIONAL — CONFIRM WITH BACKEND.** Confirm permitted inventory fields and status enums. Never include device secrets. |
| `assignDevice({ deviceId, patientId })` | Returns the assignment and linked device/patient summaries. Mock unavailable assignment returns `409`. | `POST /device-assignments` | **PROVISIONAL — CONFIRM WITH BACKEND.** Confirm assignment lifecycle, authorization, conflict rules, and request/response fields. |
| `getPatientOverview(patientId)` | Returns `{ patient, device, session, readings, predictions, freshness, dataMode, mlAvailable }`; missing records return `404`. | `GET /patients/{patientId}/monitoring` | **PROVISIONAL — CONFIRM WITH BACKEND.** Confirm composition endpoint vs separate endpoints, reading gaps, measured/received timestamps, signal-quality scale, prediction meaning, and ML availability contract. |

## Integration checklist

- [ ] Confirm authentication and current-user/session contract.
- [ ] Confirm patient list, create, and validation/error contracts.
- [ ] Confirm device inventory and assignment/release contracts.
- [ ] Confirm monitoring session and patient overview data contracts.
- [ ] Confirm sensor units, timestamp timezone, data freshness threshold, and gap representation.
- [ ] Confirm the scale/meaning of `signal_quality` before mapping it to the UI percentage.
- [ ] Confirm prediction labels and review semantics; do not present model output as a diagnosis.
- [ ] Replace mock implementations behind the existing service functions and map DTOs into UI view models.
- [ ] Verify authorization failures, validation conflicts, empty results, stale data, and network failures.
- [ ] Keep mock mode available only if desired and approved for the integration environment.

The frontend does not connect directly to PostgreSQL or to the Python simulator/ML service.
