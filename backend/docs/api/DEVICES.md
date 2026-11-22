# SomniSense API — Devices

**Status:** Implemented and tested (Stage 6, 2026-11-21). The frontend may replace its device inventory
and registration mocks. Assignments to patients come in Stage 7. Session cookie + CSRF header as in
[AUTH.md](AUTH.md). Errors follow [ERRORS.md](ERRORS.md).

**Roles:** `ADMIN` and `OPERATOR` can register, view, edit and change enrollment.
**Deactivate and reactivate are `ADMIN` only.** Devices are never deleted.

## Three separate states

These are three independent states. Never merge them in the UI.

| Field | Values | Who changes it |
|---|---|---|
| `active` | `true` / `false` | `ADMIN`, through deactivate and reactivate |
| `enrollmentStatus` | `PENDING`, `ENROLLED`, `SETUP_REQUIRED` | staff, through `PATCH /enrollment` |
| `connectivityStatus` | `CONNECTED`, `STALE`, `DISCONNECTED`, `UNKNOWN` | **nobody**; computed from `lastSeenAt` |

**Enrollment:**

- New `SIMULATED` devices start as `ENROLLED`.
- New `PHYSICAL` devices start as `PENDING`.
- Only `ENROLLED` **and** active devices can be assigned to a patient (Stage 7).

**Connectivity** is computed from `lastSeenAt` each time you request it, so it is always current:

| Value | Meaning |
|---|---|
| `CONNECTED` | data arrived within the last 30 s |
| `STALE` | the last data is 30–120 s old |
| `DISCONNECTED` | the last data is more than 120 s old |
| `UNKNOWN` | the device has never sent data |

The thresholds can be changed in configuration (`somnisense.devices.stale-after` and
`somnisense.devices.disconnected-after`). Since Stage 9, `lastSeenAt` is set to the server's receipt time
whenever an ingestion batch for the device stores or re-confirms at least one reading (see
[INGESTION.md](INGESTION.md)).

> **Copy rule:** `CONNECTED` / green only means data is arriving. It never means the patient is medically fine.

## Endpoints

| Method | Path | Body | Success | Typical errors |
|---|---|---|---|---|
| GET | `/api/v1/devices` | — | `200` Page of Device | `400` (bad filter, sort or page) |
| GET | `/api/v1/devices/{id}` | — | `200` Device | `404` |
| POST | `/api/v1/devices` | RegisterDevice | `201` Device + `Location` | `400 VALIDATION_FAILED`, `400 MALFORMED_REQUEST` (unknown type), `409 CONFLICT` (code taken) |
| PUT | `/api/v1/devices/{id}` | `{"modelName","serialNumber"}` | `200` Device | `400`, `404` |
| PATCH | `/api/v1/devices/{id}/enrollment` | `{"enrollmentStatus":"ENROLLED"}` | `200` Device | `400`, `404` |
| POST | `/api/v1/devices/{id}/deactivate` | — | `200` Device | `403` (Operator), `404` |
| POST | `/api/v1/devices/{id}/reactivate` | — | `200` Device | `403` (Operator), `404` |

Repeating an action that is already done returns `200` with no change and no new audit row.

**PUT replaces** `modelName` and `serialNumber`, so a value you leave out is cleared. `deviceCode` and
`deviceType` can never be changed; if a request includes them, they are ignored.

### List parameters

| Param | Default | Rules |
|---|---|---|
| `deviceType` | — | `PHYSICAL` or `SIMULATED` |
| `enrollmentStatus` | — | `PENDING`, `ENROLLED` or `SETUP_REQUIRED` |
| `active` | — | `true` / `false` |
| `connectivity` | — | `CONNECTED`, `STALE`, `DISCONNECTED` or `UNKNOWN`; uses the same thresholds as the displayed status |
| `q` | — | case-insensitive "contains" on device code, model name or serial number |
| `page` / `size` | `0` / `20` | `size` 1–100 |
| `sort` | `createdAt` | `deviceCode`, `createdAt` or `lastSeenAt` |
| `direction` | `desc` | `asc` or `desc` |

## Shapes

**RegisterDevice**

```json
{ "deviceCode": "OXI-0042", "deviceType": "PHYSICAL", "modelName": "Contec CMS50D", "serialNumber": "SN-1" }
```

| Field | Required | Rules |
|---|---|---|
| `deviceCode` | no | 3–50 characters from `A-Z a-z 0-9 - _`; stored upper-case; spaces around it are trimmed. **If left out, the backend generates one like `DEV-H2FP2ABH`.** Fixed after registration. |
| `deviceType` | yes | `PHYSICAL` or `SIMULATED`; fixed after registration |
| `modelName` | no | max 100 characters |
| `serialNumber` | no | max 100 characters |

**Device** (in both the list and the detail view):

```json
{
  "id": "6ad4cb7f-c05a-41b1-9a2a-485ffd5b4976",
  "deviceCode": "DEV-H2FP2ABH",
  "deviceType": "SIMULATED",
  "modelName": "Python virtual oximeter",
  "serialNumber": null,
  "active": true,
  "enrollmentStatus": "ENROLLED",
  "connectivityStatus": "UNKNOWN",
  "lastSeenAt": null,
  "registeredBy": "<staff user id>",
  "createdAt": "2026-11-21T13:30:36.593173Z",
  "updatedAt": "2026-11-21T13:30:36.593173Z"
}
```

`currentAssignment` (Stage 7) is added after `lastSeenAt`. It holds
`{ "assignmentId", "patientId", "patientCode", "assignedAt" }`, or `null` when the device is not assigned.
See [ASSIGNMENTS.md](ASSIGNMENTS.md).

**Deactivating an assigned device returns `409 DEVICE_IN_USE`.** Release the assignment first.

**Security note:** no device secret, credential or token is ever accepted or returned. A device code on its
own does not prove who is sending readings. Ingestion uses a separate trusted key (decision F8, Stage 9).

## Audit entries

`user_id` is the staff member who acted; `entity_type` = `DEVICE`.

| Action | `details` |
|---|---|
| `DEVICE_REGISTERED` | `{"deviceType":"SIMULATED","codeSource":"GENERATED"\|"STAFF"}` |
| `DEVICE_UPDATED` | `{"fields":["modelName","serialNumber"]}` |
| `DEVICE_ENROLLMENT_CHANGED` | `{"from":"PENDING","to":"ENROLLED"}` |
| `DEVICE_DEACTIVATED` | — |
| `DEVICE_REACTIVATED` | — |
