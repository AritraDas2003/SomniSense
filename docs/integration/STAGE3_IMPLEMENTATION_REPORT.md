# SOMNISENSE — STAGE 3 IMPLEMENTATION REPORT
## Python FastAPI Model C Inference Sidecar & Spring Boot Integration

> **Date:** 2026-11-26  
> **Branch:** `stage3/fastapi-model-c-integration` (branched from `stage2/prediction-persistence` @ `95880508f84dc830fb4d49b29618bee1e73d20c9`)  
> **Implementation Commit:** `398b41d6bf3f461c05b15aaada6d64547e5a7e64` (`feat(ai): integrate frozen Model C inference sidecar`)  
> **Authoritative Workspace:** `C:\Users\LORD\Downloads\Minor_Project\SomniSense_Team`  
> **Team Remote (`origin`):** `https://github.com/AritraDas2003/SomniSense` (branch `stage3/fastapi-model-c-integration`)  
> **Personal Remote (`personal`):** `https://github.com/lordsins000/SomniSense` (branch `stage3/fastapi-model-c-integration`)  
> **Status:** **VERIFIED & PUBLISHED** (181/181 Java Maven tests + 7/7 Python pytest tests passing)

---

## 1. Executive Summary

Stage 3 implemented a self-contained internal Python FastAPI inference sidecar (`ai-service/`) hosting the frozen **Phase 7 Model C** pipeline (`phase7_model_c:1.0.0`), paired with a deterministic Spring Boot HTTP client and orchestration service (`com.somnisense.backend.ai`).

Spring Boot remains the sole public API and PostgreSQL owner. Python has zero database drivers or connections, and React never communicates with the FastAPI sidecar directly.

---

## 2. Architecture & Policy Decisions

1. **`VALID`-Only Inference Policy:**
   - Only `VALID` 60-second windows (`>= 80%` valid coverage and `longest_gap <= 5s` on both SpO₂ and HR) reach `ModelCInference.predict_observation` (`predict_proba`).
   - `DEGRADED` (`PARTIAL_VALID`) and `REJECTED` (`EMPTY`, `GAP_DOMINATED`, `INSUFFICIENT`) windows are blocked prior to feature extraction and `predict_proba`, and persisted with `PredictionStatus.REJECTED`, `predictedClass = null`, `confidenceScore = null`, and the exact quality-gate `errorMessage`.
   - Stage 2 `PredictionService` was updated backward-compatibly to preserve `InputQualityStatus.DEGRADED` separately from `InputQualityStatus.REJECTED` under `PredictionStatus.REJECTED`.
2. **360-Second Causal Context & 1 Hz Timestamp Contract:**
   - Spring Boot queries `[windowEnd - 360s, windowEnd)` via `SensorReadingRepository.findWindowOrdered` (`ORDER BY r.measuredAt ASC, r.receivedAt ASC, r.id ASC`).
   - FastAPI additionally sorts by `(measuredAt ASC, receivedAt ASC, id ASC, input_index ASC)`, maps sub-second timestamps with `math.floor`, applies deterministic last-write-wins for duplicate buckets, preserves `np.nan` for missing seconds, and sanitizes `spo2 ∈ [50.0, 100.0]` and `heartRate ∈ [30.0, 220.0]`.
   - Buckets `0..299` warm up `StreamingRollingBaseline` (300s 90th percentile, `min_valid_samples=240`, `outage_gap_threshold=5s`, `warmup_streak_required=60s`); buckets `300..359` form the 60s evaluation window.
3. **Resilience & Idempotency:**
   - `AiSidecarClient` enforces configurable connect (`2s`) and read (`5s`) timeouts, sends `X-Internal-AI-Key` and `X-Correlation-Id`, and never logs secret values.
   - `WindowEvaluationService` catches `AiSidecarException` (timeouts, 5xx, transport errors) and persists `PredictionStatus.ERROR` safely without fabricated class/confidence values.
   - All persistence delegates to Stage 2 `PredictionService.recordPrediction(...)`, preserving `ux_predictions_session_model_window` idempotency.

---

## 3. Components Added & Updated

### Python Sidecar (`ai-service/`)
- `ai-service/artifacts/model_c/`: Frozen `model_c_pipeline.joblib`, `feature_schema.json`, `model_metadata.json`, `model_manifest.json`, `inference.py`.
- `ai-service/app/config.py`, `schemas.py`, `signal_processing.py`, `model_engine.py`, `main.py` (`GET /internal/ai/health`, `GET /internal/ai/ready`, `POST /internal/ai/evaluate-window`).
- `ai-service/tests/test_artifact_and_parity.py`, `test_fastapi_sidecar.py`.
- `ai-service/requirements.txt`, `.env.example`, `README.md`.

### Spring Boot (`backend/`)
- `SensorReadingRepository.java`: Added deterministic `findWindowOrdered(sessionId, from, to)`.
- `PredictionService.java`: Backward-compatible `REJECTED` + `InputQualityStatus.DEGRADED` preservation.
- `com.somnisense.backend.ai`: `AiProperties`, `AiReadingSample`, `AiEvaluateWindowRequest`, `AiEvaluateWindowResponse`, `AiSidecarException`, `AiSidecarClient`, `WindowEvaluationService`.
- `application.properties`, `application-test.properties`, `backend/.env.example`.
- `WindowEvaluationServiceTest.java`, `PredictionServiceTest.java`.

### Documentation
- `docs/integration/STAGE3_PRE_IMPLEMENTATION_AUDIT.md`, `docs/integration/STAGE3_IMPLEMENTATION_REPORT.md`
- `backend/docs/api/PREDICTIONS.md`, `backend/docs/api/README.md`

---

## 4. Verification Results

### Python AI Sidecar (`pytest ai-service/tests -v`)
- **7 passed, 0 failed, 0 skipped in 1.76s**:
  - `test_frozen_artifact_hashes_and_zero_fitting` (`faef00b0...`, `c4d1b2e2...`, unchanged pipeline parameters)
  - `test_16_feature_schema_ordering_and_probability_parity` (`< 1e-9` vs direct pipeline, threshold `0.50`)
  - `test_1hz_timestamp_bucketing_duplicates_subsecond_and_sanitization`
  - `test_health_ready_and_auth_enforcement` (`200`, `401`, `503`)
  - `test_evaluate_window_normal_and_apnea_valid_windows` (`COMPLETED`/`NORMAL` and `NEEDS_REVIEW`/`APNEA_EVENT`)
  - `test_degraded_and_rejected_windows_never_reach_predict_proba` (spy confirms `call_count == 0`)
  - `test_invalid_window_boundaries_return_400`

### Java Backend (`.\scripts\qa\run_stage2_backend_tests.ps1` → `.\mvnw.cmd test` on `somnisense_test_db`)
- **181 passed, 0 failed, 0 errors, 0 skipped across 24 test classes (`BUILD SUCCESS`)**:
  - `WindowEvaluationServiceTest`: `5/5` passed (`10.524s`) — deterministic reading ordering, `X-Internal-AI-Key`/`X-Correlation-Id`, idempotent `NEEDS_REVIEW` persistence, `DEGRADED`/`REJECTED` preservation, `500`/timeout `ERROR` persistence, invalid/cancelled/unknown/future-dated window rejection.
  - `PredictionServiceTest`: `5/5` passed (`0.625s`)
  - `PredictionApiTest`: `3/3` passed (`1.23s`)
  - `PredictionMigrationAndConstraintsTest`: `3/3` passed (`0.393s`)
  - All existing 165 Stage 1/2 backend tests: `165/165` passed.

---

## 5. Protected Assets & Security Verification

- **Original `SomniSense` prototype & Simulator S1–S3:** Untouched.
- **Frozen Model C artifact & feature schema:** Untouched; SHA-256 `faef00b08c6406bb0277c6ca05f15239acfc8365561d9c9a07eba3554939a6ca` and `c4d1b2e26a15569cef6328d8e4d06f5dc5677f0d8b74f709f30c104715d472f7` verified.
- **Flyway `V1`–`V5` & Stage 1 React frontend:** Untouched.
- **No Python DB access:** Zero PostgreSQL drivers or connections in `ai-service/`.
- **No secrets in Git:** Real keys remain excluded via `.env` / environment variables.

---

## 6. Remaining Limitations & Stage 4 Scope

- **Educational Prototype Only:** Not clinically validated and not deployed for live clinical production.
- **Deferred to Stage 4:**
  - Automated sliding-window inference scheduler (10s stride triggering `WindowEvaluationService`).
  - Virtual simulator S1–S3 ingestion bridge and lifecycle orchestration.
  - React frontend live prediction polling/display (`PatientDetailsPage`, `DashboardPage` `itemsNeedingReview`) and full multi-patient end-to-end validation.
