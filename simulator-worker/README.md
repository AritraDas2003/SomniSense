# SomniSense Standalone Simulator Worker (Stage 4C)

Standalone FastAPI worker process that generates deterministic S1–S3 physiological waveforms (`normal_sleep`, `severe_osa`, `cheyne_stokes_csa`, `moderate_hypopnea`) and S3 quality degradations (`CLEAN`, `NOISY`, `TRANSIENT_DROPOUT`, `DEGRADED`, `PROLONGED_DISCONNECT`), streaming bounded reading batches exclusively to Spring Boot's `POST /api/v1/ingestion/readings`.

## Architectural Boundaries
- **Zero PostgreSQL Access:** Contains no PostgreSQL driver (`psycopg`, `asyncpg`, `sqlalchemy`) and holds zero database credentials.
- **Zero Model C / Inference Coupling:** Never imports or calls the frozen Model C pipeline or `/internal/ai/*`.
- **Backend Control & Fencing:** Accepts lifecycle commands (`start`, `pause`, `resume`, `stop`, `status`) from Spring Boot using `X-Internal-Sim-Key`, includes `(simulationRunId, workerGeneration, workerInstanceId)` on every ingestion batch, immediately terminates orphaned tasks upon `409` fencing rejection, and resumes deterministically from `lastStep + 1`.

## Portable Setup & Execution

From `SomniSense_Team/simulator-worker`:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

Set required environment variables (`>= 32` chars for shared keys):

```powershell
$env:SIM_WORKER_INTERNAL_KEY = "replace-with-32-plus-char-internal-sim-key"
$env:INGESTION_API_KEY = "replace-with-32-plus-char-ingestion-key"
$env:BACKEND_BASE_URL = "http://127.0.0.1:8080"
uvicorn app.main:app --host 127.0.0.1 --port 8095
```

## Tests

```powershell
pytest tests -v
```
