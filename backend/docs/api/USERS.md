# SomniSense API — Staff Management (Admin only)

**Status:** Implemented and tested (Stage 4, 2026-11-21). The frontend may replace its Manage Staff mocks.
Session cookie + CSRF header as in [AUTH.md](AUTH.md). Errors follow [ERRORS.md](ERRORS.md).

**Every endpoint below requires role `ADMIN`.** An `OPERATOR` gets `403 FORBIDDEN`; a client that is not
logged in gets `401 UNAUTHORIZED`.

## Endpoints

| Method | Path | Body | Success | Typical errors |
|---|---|---|---|---|
| GET | `/api/v1/users` | — | `200` Page of User | `400 VALIDATION_FAILED`, `400 INVALID_REQUEST` (bad sort) |
| GET | `/api/v1/users/{id}` | — | `200` User | `404 RESOURCE_NOT_FOUND`, `400` (id is not a UUID) |
| POST | `/api/v1/users` | `{"username","email","role"}` | `201` CreatedUser + `Location` header | `400 VALIDATION_FAILED`, `400 MALFORMED_REQUEST` (unknown role), `409 CONFLICT` (username/email taken) |
| PATCH | `/api/v1/users/{id}/role` | `{"role":"ADMIN"\|"OPERATOR"}` | `200` User | `400 SELF_ACTION_NOT_ALLOWED`, `409 LAST_ACTIVE_ADMIN`, `404` |
| POST | `/api/v1/users/{id}/deactivate` | — | `200` User | `400 SELF_ACTION_NOT_ALLOWED`, `409 LAST_ACTIVE_ADMIN`, `404` |
| POST | `/api/v1/users/{id}/reactivate` | — | `200` User | `404` |
| POST | `/api/v1/users/{id}/reset-password` | — | `200 {"temporaryPassword":"..."}` | `400 SELF_ACTION_NOT_ALLOWED`, `404` |

All POST and PATCH requests need the `X-XSRF-TOKEN` header.

### List parameters (`GET /api/v1/users`)

| Param | Default | Rules |
|---|---|---|
| `role` | — | `ADMIN` or `OPERATOR` |
| `active` | — | `true` / `false` |
| `q` | — | case-insensitive "contains" search on username or email, max 100 characters |
| `page` | `0` | zero-based, ≥ 0 |
| `size` | `20` | 1–100 |
| `sort` | `createdAt` | `username`, `createdAt` or `lastLoginAt` |
| `direction` | `desc` | `asc` or `desc` |

### Shapes

**User**

```json
{
  "id": "179e6dbe-94e4-4292-a659-65d272b5187a",
  "username": "demo.operator",
  "email": "demo.operator@somnisense.local",
  "role": "OPERATOR",
  "active": true,
  "createdAt": "2026-11-21T13:11:20.213709Z",
  "updatedAt": "2026-11-21T13:11:20.213709Z",
  "lastLoginAt": null
}
```

- `updatedAt` changes when the account changes (role, active state, password). It does not change on login.
- `lastLoginAt` is `null` until the first login.

**Page** (the same shape for every list in the API):

```json
{ "content": [ /* User */ ], "page": 0, "size": 20, "totalElements": 2, "totalPages": 1 }
```

**CreatedUser** (the `201` response of create):

```json
{ "user": { /* User */ }, "temporaryPassword": "Kp7mQ2xRt9WvB4nE" }
```

### Create rules

- `username`: 3–50 characters from `A-Z a-z 0-9 . _ -`. It is stored lower-case and cannot contain `@`.
- `email`: a valid email address, max 255 characters. It is stored lower-case.
- `role`: required, `ADMIN` or `OPERATOR`.
- **There is no password field.** The server generates a 16-character temporary password and returns it
  **once**. Show it to the Admin to hand over, and never store it in the frontend. If it is lost, use
  reset-password. The staff member should change it with `POST /api/v1/auth/password`.

## Business rules (enforced by the backend)

- **At least one active Admin must always remain.**
  - Deactivating or demoting the last one returns `409 LAST_ACTIVE_ADMIN`.
  - The rule is safe even when two Admins act at the same moment, because the database rows are locked during the check.
- **No self-service through these endpoints.** An Admin cannot deactivate, demote or reset the password of
  their own account (`400 SELF_ACTION_NOT_ALLOWED`). They change their own password through
  `POST /api/v1/auth/password` instead.
- **Accounts are never deleted.** Deactivate them instead, because audit history keeps referring to them.
- **Effect on the affected user's open sessions:**
  - Deactivation, a role change or a password reset ends them on that user's next request (`401 SESSION_INVALID`).
  - A deactivated user also cannot log in.
- **Repeating an action is safe.** Deactivating an inactive user or setting the same role returns `200`
  with no change and no new audit row.
- **Ask for confirmation in the UI** before a role change, deactivation or reset (UI plan C80). The backend
  does not require a confirmation flag.

## Audit entries

`user_id` is the Admin who acted. `entity_type` = `USER` and `entity_id` = the affected account.

| Action | `details` |
|---|---|
| `USER_CREATED` | `{"role":"OPERATOR"}` |
| `USER_ROLE_CHANGED` | `{"from":"OPERATOR","to":"ADMIN"}` |
| `USER_DEACTIVATED` | — |
| `USER_REACTIVATED` | — |
| `USER_PASSWORD_RESET` | — (the password is never logged) |
