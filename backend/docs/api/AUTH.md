# SomniSense API — Authentication Contract

**Status:** Implemented and tested (Stage 3, 2026-11-21). The frontend may replace its login/me/logout mocks.
Errors follow [ERRORS.md](ERRORS.md).

## Design in one paragraph

Staff log in once. The server keeps the login in a **server-side session**, and the browser holds it in an
`HttpOnly` cookie called `JSESSIONID`. JavaScript cannot read that cookie, and the browser sends it
automatically. Because the cookie is sent automatically, every state-changing request (POST/PUT/PATCH/DELETE)
must also carry a **CSRF token**. The server puts that token in the readable cookie `XSRF-TOKEN`, and the client
copies it into the `X-XSRF-TOKEN` header. There are no JWTs and no tokens in `localStorage`.

- Idle timeout: **60 minutes** (`SESSION_TIMEOUT`). Any request resets it.
- Roles: `ADMIN`, `OPERATOR`. The backend enforces them on every request.
- If an account is deactivated or its role changes, its session ends on the **next** request
  (`401 SESSION_INVALID`).

## Endpoints

| Method | Path | Auth | Request | Success | Errors |
|---|---|---|---|---|---|
| GET | `/api/v1/auth/csrf` | Public | — | `200 {"headerName":"X-XSRF-TOKEN","token":"..."}` and sets the `XSRF-TOKEN` cookie | — |
| POST | `/api/v1/auth/login` | Public + CSRF | `{"login":"admin or admin@x.org","password":"..."}` | `200` CurrentUser, sets `JSESSIONID`, **replaces** `XSRF-TOKEN` | `400 VALIDATION_FAILED`, `401 INVALID_CREDENTIALS`, `403 CSRF_TOKEN_INVALID` |
| GET | `/api/v1/auth/me` | Logged in | — | `200` CurrentUser | `401 UNAUTHORIZED`, `401 SESSION_INVALID` |
| POST | `/api/v1/auth/logout` | CSRF | — | `204` (session ended, cookies cleared) | `403 CSRF_TOKEN_INVALID` |
| POST | `/api/v1/auth/password` | Logged in + CSRF | `{"currentPassword":"...","newPassword":"12-72 chars"}` | `204`; this session stays logged in and the user's **other** sessions end | `400 CURRENT_PASSWORD_INCORRECT`, `400 VALIDATION_FAILED`, `400 INVALID_REQUEST` (new password same as old) |
| GET | `/api/v1/health`, `/actuator/health` | Public | — | `200` | — |

`login` accepts a **username or an email**. It is case-insensitive and ignores surrounding spaces.
`password` is 1–72 characters.

Wrong password, unknown account and deactivated account **all** return the same
`401 INVALID_CREDENTIALS` "Invalid username/email or password". This is deliberate, so the response never
reveals which accounts exist.

### CurrentUser

```json
{
  "id": "b70b285e-4176-4425-90d4-ec969bfe2230",
  "username": "admin",
  "email": "admin@somnisense.local",
  "role": "ADMIN",
  "active": true,
  "lastLoginAt": "2026-11-21T12:59:57.553769Z",
  "createdAt": "2026-11-21T12:59:56.713628Z"
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | UUID string | |
| `username` | string | lower-case |
| `email` | string | lower-case |
| `role` | `"ADMIN"` \| `"OPERATOR"` | drives navigation; the backend still enforces it |
| `active` | boolean | always `true` for a logged-in user |
| `lastLoginAt` | ISO-8601 UTC \| null | |
| `createdAt` | ISO-8601 UTC | |

A password or password hash is **never** returned.

## Frontend (React + axios) setup

```js
const api = axios.create({
  baseURL: 'http://localhost:8080/api/v1',
  withCredentials: true,     // send/receive the session + CSRF cookies across ports
  withXSRFToken: true,       // axios >= 1.6: also attach X-XSRF-TOKEN on cross-origin requests
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
});

// App start-up:
await api.get('/auth/csrf');                 // makes sure the XSRF-TOKEN cookie exists
const me = await api.get('/auth/me')         // 200 -> logged in, 401 -> show the login page
  .catch(() => null);

// Login / logout:
await api.post('/auth/login', { login, password });
await api.post('/auth/logout');
```

How to handle errors:

- `401` on any call means the session is missing or expired. Go to the login page.
- `401 SESSION_INVALID` means the account changed. Show "please log in again".
- `403 FORBIDDEN` means the role is not allowed. Show the access-denied page.
- `403 CSRF_TOKEN_INVALID` means the token is missing or stale. Call `GET /auth/csrf` once and retry.

Allowed browser origins (CORS): `http://localhost:5173` and `http://127.0.0.1:5173`
(set with `CORS_ALLOWED_ORIGINS`).

## Postman

1. Create an environment with `baseUrl = http://localhost:8080/api/v1`.
2. Postman keeps cookies per domain automatically, so the session works across requests.
3. Add this **collection-level pre-request script** so every request sends the CSRF header:

   ```js
   const token = pm.cookies.get('XSRF-TOKEN');
   if (token) { pm.request.headers.upsert({ key: 'X-XSRF-TOKEN', value: token }); }
   ```

   If `pm.cookies` is empty, open the cookie manager under the Send button and allow-list `localhost`.
4. Send `GET {{baseUrl}}/auth/csrf`, then `POST {{baseUrl}}/auth/login` with a raw JSON body.
5. `GET {{baseUrl}}/auth/me` now returns your user.

Do not save real passwords in an exported collection. Use a Postman environment variable marked *secret*.

## First admin account

Created at startup **only while the `users` table is empty**, from `SOMNISENSE_ADMIN_USERNAME`,
`SOMNISENSE_ADMIN_EMAIL` and `SOMNISENSE_ADMIN_PASSWORD` (12–72 characters). See `.env.example`.
On a developer machine these live in the git-ignored `.env`.

## Audit entries written

| Action | When | `details` |
|---|---|---|
| `ADMIN_BOOTSTRAPPED` | First admin created | `{"role":"ADMIN"}` |
| `LOGIN_SUCCESS` | Successful login | — |
| `LOGIN_FAILED` | Failed login; `user_id` is set only if the login matched an account | `{"reason":"BAD_CREDENTIALS"\|"ACCOUNT_DISABLED"}` |
| `LOGOUT` | Logout | — |
| `SESSION_REVOKED` | Session ended because the account was deactivated, deleted, its role changed, or its password changed/was reset | `{"reason":"ACCOUNT_DEACTIVATED"\|"ROLE_CHANGED"\|"PASSWORD_CHANGED"\|"ACCOUNT_MISSING"}` |
| `PASSWORD_CHANGED` | A user changed their own password | — |

The typed login text and passwords are never stored.
