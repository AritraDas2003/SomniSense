# SomniSense Internal AI Sidecar (`ai-service`)

Internal FastAPI service hosting the frozen **Phase 7 Model C** sleep apnea inference pipeline (`phase7_model_c:1.0.0`).

- **Internal only:** Bound to `127.0.0.1:8090` by default; called exclusively by Spring Boot using `X-Internal-AI-Key`.
- **No database access:** Never connects to PostgreSQL.
- **Frozen Model C:** Verifies SHA-256 checksums of `model_c_pipeline.joblib` and `feature_schema.json` at startup and enforces `VALID`-only inference (`DEGRADED` and `REJECTED` windows never invoke `predict_proba`).
- **Endpoints:** `GET /internal/ai/health`, `GET /internal/ai/ready`, `POST /internal/ai/evaluate-window` (360s causal context: 300s baseline warmup + 60s window).
- **Verified:** `pytest ai-service/tests -v` passes 7/7 tests (1.76s). Note: this is an educational research prototype, not clinically validated or deployed to production.

## Run locally (using the existing Python `.venv`)

```powershell
$env:AI_SERVICE_API_KEY = "your-32-plus-character-internal-secret-key"
& "C:\Users\LORD\Downloads\Minor_Project\SomniSense\.venv\Scripts\python.exe" -m uvicorn app.main:app --app-dir ai-service --host 127.0.0.1 --port 8090
```

## Run tests

```powershell
& "C:\Users\LORD\Downloads\Minor_Project\SomniSense\.venv\Scripts\python.exe" -m pytest ai-service/tests -v
```
