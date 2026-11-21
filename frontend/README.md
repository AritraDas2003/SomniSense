# SomniSense frontend

SomniSense is an educational sleep-monitoring prototype for staff. This frontend currently implements the first mock-backed workflow milestone:

**Mock login → dashboard/patient list → add patient → assign device → patient overview**

There is no public landing page. All content is demo data unless explicitly integrated with a confirmed Spring Boot contract.

## Run locally

```sh
npm run dev
```

The app starts in mock mode by default. Demo sign-in accounts:

| Role | Email | Password |
| --- | --- | --- |
| Admin | `admin@somnisense.local` | `SomniDemo123!` |
| Operator | `operator@somnisense.local` | `SomniDemo123!` |

These are local mock credentials only; they are not production accounts.

Authentication state is managed in Redux Toolkit (`auth` slice); the app is mounted under the Redux `Provider`. Login, patient registration, and device assignment use React Hook Form for validation and submission state.

## API mode

Mock mode is enabled unless `VITE_USE_MOCK_API=false` is set. To configure the provisional Spring Boot adapter, set:

```env
VITE_USE_MOCK_API=false
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

Do not put secrets in Vite environment variables. The service endpoint and DTO assumptions are provisional; review [docs/API_INTEGRATION_CHECKLIST.md](./docs/API_INTEGRATION_CHECKLIST.md) with the backend team before connecting a real environment.

Mock fixtures are held in memory and reset on page reload. The sample simulated stream includes a missing-data gap and a stale-data fixture. It is not live telemetry and must not be interpreted as a clinical measurement or diagnosis. Physical-source mock records intentionally have no fabricated readings.

## Validation

```sh
npm run build
npm run lint
```

The build is expected to succeed. The repository-wide lint command currently reports existing issues in the starter UI button component and `vite.config.js`; lint the changed modules separately to check this milestone.

## Current scope

Implemented: mock login/logout and session-expiry handling for HTTP `401`, responsive navigation shell, dashboard, patient search/filter/list, patient registration, device inventory/assignment, and patient overview with separate SpO₂ and heart-rate charts.

Not implemented yet: staff management, alert/event review, simulator controls, dedicated session history, and reports. The frontend does not connect directly to PostgreSQL or to the Python simulator/ML service.
