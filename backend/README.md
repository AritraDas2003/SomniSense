# SomniSense — Spring Boot Backend

The product API for SomniSense, an **educational, simulator-first sleep-monitoring prototype**. It is not a
medical device. The backend handles:

- staff login and roles;
- patients, devices, device assignments and monitoring sessions;
- machine ingestion of SpO₂ / heart-rate readings, and reading queries for the React frontend.

**API documentation:** [docs/api/README.md](docs/api/README.md) (start here).

## Stack

Java 21, Spring Boot 4.2, Spring Security, Spring Data JPA (Hibernate 7), Flyway, PostgreSQL 18, and Maven
through the wrapper (`mvnw`). No Lombok.

## 1. Prerequisites

- JDK 21
- PostgreSQL running on `localhost:5432` with:
  - `somnisense_db`: the development database. Flyway creates the tables automatically.
  - `somnisense_test_db`: an empty database used **only** by the automated tests.

  ```sql
  CREATE DATABASE somnisense_db;
  CREATE DATABASE somnisense_test_db;
  ```

## 2. Local secrets: `.env`

Copy `.env.example` to `.env` in this folder and fill in your own values. `.env` is git-ignored, so it is
**never committed**.

| Variable | What it is |
|---|---|
| `DB_PASSWORD` | your PostgreSQL password (`DB_URL` and `DB_USERNAME` are optional overrides) |
| `SOMNISENSE_ADMIN_USERNAME` / `_EMAIL` / `_PASSWORD` | the first Admin account, created **only while the users table is empty**. Password: 12–72 characters |
| `INGESTION_API_KEY` | at least 32 random characters. The simulator or gateway sends it as `X-Ingestion-Key` |

Optional variables: `SESSION_TIMEOUT` (default `60m`), `SESSION_COOKIE_SECURE` (`true` behind HTTPS) and
`CORS_ALLOWED_ORIGINS` (default `http://localhost:5173,http://127.0.0.1:5173`).

## 3. Run

From this folder (`backend/`):

```powershell
.\mvnw.cmd spring-boot:run
```

You can also run `BackendApplication` from IntelliJ. The working directory must be this folder so that
`.env` is found.

What happens at startup:

1. Flyway applies the migrations in `src/main/resources/db/migration`: `V1` is the approved schema, and
   `V2`–`V4` are approved indexes only.
2. Hibernate **validates** the mappings. It never changes tables (`ddl-auto=validate`).
3. The first Admin is created if no users exist yet.

Check that it's up: `GET http://localhost:8080/api/v1/health` returns `{"status":"UP",...}`.

## 4. Test

```powershell
.\mvnw.cmd test
```

- The tests use **`somnisense_test_db` only**. The test cleaner refuses to run on any other database.
- Expected result: `Tests run: 165, Failures: 0`.
- There are unit tests, HTTP tests through the real security chain, PostgreSQL index tests, and
  concurrency tests (simultaneous assign, start or ingest).

## 5. Postman (manual end-to-end check)

The files are in `postman/`:

1. Import `SomniSense.postman_collection.json` and `SomniSense-Local.postman_environment.json` into the Postman app.
2. Select the **SomniSense Local** environment. Set the **secret** values `adminPassword` (from `.env`) and
   `ingestionKey` (from `.env`). Nothing secret is stored in the files.
3. Start the backend, then **Run collection**. The 10 folders run in order:
   - health and login;
   - staff, patients, devices;
   - assignment, session;
   - machine ingestion (including a retry);
   - readings, then cleanup.

   Each folder also contains one deliberate failure case. 32 requests, 69 checks.

The run creates clearly named demo records (`Postman Demo Patient …`, a simulated device, an ended session
with 3 synthetic readings). It ends the session, releases the device and logs out.

**Full test suite:** import `SomniSense-FULL-Tests.postman_collection.json` too (14 folders, 194 requests, 449 checks:
every endpoint with success and failure cases, in run order). Use it the same way, with Run collection. A human-readable
list of the same checks is in `postman/API_TEST_PLAN.md`.

Command-line alternative (needs Node.js):

```powershell
npx newman run postman/SomniSense.postman_collection.json -e postman/SomniSense-Local.postman_environment.json --env-var "adminPassword=<from .env>" --env-var "ingestionKey=<from .env>"
```

## 6. Project layout

```
src/main/java/com/somnisense/backend/
  common/        error format, exceptions, correlation IDs, paging, shared enums/helpers
  security/      session login, CSRF/CORS, roles, first-admin seed; security/ingestion/ = machine key chain
  auth/          login, logout, current user, change password
  user/          staff management (Admin)
  audit/         audit_logs writer
  patient/       patients + contact + medical details
  device/        devices, connectivity policy
  assignment/    device ↔ patient assignments
  session/       monitoring sessions
  ingestion/     POST /ingestion/readings (machine)
  reading/       reading history and latest values (staff)
src/main/resources/db/migration/   Flyway migrations (never edit an applied file; add V5, V6, …)
docs/api/                          API contracts for the frontend team
postman/                           collection + environment (no secrets)
```

## 7. Ground rules

These hold for every change:

- **Schema changes** need the backend lead's approval and are made only through a new Flyway migration.
- **Never** switch `ddl-auto` to `update` or `create`.
- **Secrets** live only in `.env` or the environment.
- **Missing readings stay `null`**, never 0. Out-of-range values are rejected, never clamped.
- **Model output** is a review prompt, not a diagnosis. The Python AI/simulator integration is not built
  yet: it waits for the AI lead's contract.
