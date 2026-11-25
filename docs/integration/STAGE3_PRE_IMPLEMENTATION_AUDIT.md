# SOMNISENSE — STAGE 3 PRE-IMPLEMENTATION AUDIT
## Python FastAPI Model C Inference Sidecar Architecture & Frozen Artifact Verification

> **Audit Date:** 2026-11-26  
> **Document Type:** Historical Pre-Implementation Audit (Stage 3 Planning Baseline)  
> **Baseline Audited:** `stage2/prediction-persistence` @ `95880508f84dc830fb4d49b29618bee1e73d20c9`  
> **Preserved Prototype Workspace (Read-Only):** `C:\Users\LORD\Downloads\Minor_Project\SomniSense`  
> **Status:** **HISTORICAL AUDIT — SUPERSEDED BY IMPLEMENTATION** (see [STAGE3_IMPLEMENTATION_REPORT.md](STAGE3_IMPLEMENTATION_REPORT.md) on `stage3/fastapi-model-c-integration` @ `398b41d6bf3f461c05b15aaada6d64547e5a7e64`)

---

## 1. Existing Source Inventory (Preserved `SomniSense` Prototype)

All files below were inspected read-only in `C:\Users\LORD\Downloads\Minor_Project\SomniSense`:

| Component | Source Path (relative to `SomniSense/`) | Role |
|---|---|---|
| **Frozen Model Artifact** | `preprocessing/phase7_sliding_window/output/model_package/model_c_pipeline.joblib` | Serialized scikit-learn `Pipeline` (`SimpleImputer` → `StandardScaler` → `LogisticRegression`) |
| **Model Metadata & Manifest** | `preprocessing/phase7_sliding_window/output/model_package/model_metadata.json`, `model_manifest.json` | Training cohort (`N=96,659`, 50 patients), pipeline parameters, artifact SHA-256 inventory |
| **16-Feature Schema** | `preprocessing/phase7_sliding_window/output/model_package/feature_schema.json` | Exact 16-predictor contract, types, nullability, cohort statistics |
| **Standalone Inference Engine** | `preprocessing/phase7_sliding_window/output/model_package/inference.py` | `ModelCInference`, `APPROVED_16_PREDICTORS`, strict input validation, `predict_proba` + `0.50` threshold |
| **Model Package Tests** | `preprocessing/phase7_sliding_window/output/model_package/tests/test_*.py` | 22 automated tests (`test_model_loading.py`, `test_input_validation.py`, `test_inference.py`) |
| **Offline Feature Pipeline** | `preprocessing/phase7_sliding_window/feature_pipeline.py`, `config.py` | Phase 7 sliding-window grid (`60s` window, `10s` stride, `1 Hz`), quality & baseline wiring |
| **Phase 5B Core Modules** | `preprocessing/phase5_feature_extraction/{quality_classifier,baseline_estimator,feature_extractor}.py` | Authoritative 5-level quality ladder, 300s causal rolling baseline, Track A/B feature math |
| **Phase 8 Runtime Prototype** | `application/processing/{feature_extraction,quality_control,signal_validation}.py`, `application/inference/model_service.py` | Streaming 16-feature extractor, `StreamingRollingBaseline`, `NormalizedQualityState`, `ModelService` |
| **Phase 8 Parity & Integration Tests** | `application/tests/test_feature_parity.py`, `test_model_integration.py` | `<= 1e-6` feature parity check vs `phase7_window_features.csv.gz` and Model C integration tests |

---

## 2. Frozen Artifact & Hash Verification

SHA-256 checksums were recomputed on 2026-11-26 and compared against `model_manifest.json`:

| Artifact | Recorded SHA-256 (`model_manifest.json`) | Verified SHA-256 (2026-11-26) | Status |
|---|---|---|---|
| `model_c_pipeline.joblib` | `faef00b08c6406bb0277c6ca05f15239acfc8365561d9c9a07eba3554939a6ca` | `faef00b08c6406bb0277c6ca05f15239acfc8365561d9c9a07eba3554939a6ca` | **MATCH** |
| `feature_schema.json` | `c4d1b2e26a15569cef6328d8e4d06f5dc5677f0d8b74f709f30c104715d472f7` | `c4d1b2e26a15569cef6328d8e4d06f5dc5677f0d8b74f709f30c104715d472f7` | **MATCH** |
| `inference.py` | `eeae1f80aff0cc477398cd7b1a3d9be3c7e5d2b4c8ee775e1edd284bd87928bb` | `eeae1f80aff0cc477398cd7b1a3d9be3c7e5d2b4c8ee775e1edd284bd87928bb` | **MATCH** |
| `model_metadata.json` | `02304cc6c5fa958d818bf899ab067c74d01c7d1a97580c63baf2ab669cf5a714` | `02304cc6c5fa958d818bf899ab067c74d01c7d1a97580c63baf2ab669cf5a714` | **MATCH** |

**Frozen Pipeline Configuration (`model_metadata.json` & `inference.py`):**
- **Model ID & Version:** `phase7_model_c` / `1.0.0` (`phase7_model_c:1.0.0`)
- **Pipeline Steps:** `["imputer", "scaler", "classifier"]`
  - `imputer`: `SimpleImputer(strategy="median")` (16 statistics)
  - `scaler`: `StandardScaler(with_mean=True, with_std=True)` (16 means & scales)
  - `classifier`: `LogisticRegression(penalty="l2", C=1.0, solver="lbfgs", max_iter=1000, random_state=42)`
- **Positive-Class Probability & Threshold:** `probs = pipeline.predict_proba(X)`, `positive_prob = float(probs[0, 1])`, `binary_pred = int(positive_prob >= 0.50)`.
- **Zero-Fitting Guarantee:** Sidecar must **never** call `fit()` or `fit_transform()`; only `predict_proba(X)` on shape `(1, 16)` `np.float64` arrays.
- **Frozen Package Tests:** Executed `pytest -p no:cacheprovider` on `model_package/tests` using `SomniSense\.venv\Scripts\python.exe` — **22/22 passed in 2.44s**.

---

## 3. Feature Parity & Preprocessing Analysis

### Exact 16-Feature Ordering (`APPROVED_16_PREDICTORS`)
1. `spo2_mean` (60s window mean, `%`)
2. `spo2_min` (60s window min, `%`)
3. `spo2_max` (60s window max, `%`)
4. `spo2_std` (60s sample std `ddof=1`, `0.0` if `n == 1`)
5. `spo2_rolling_baseline` (median of valid 1 Hz rolling baseline values in 60s window; **nullable** — imputed by `SimpleImputer` if `NaN`)
6. `spo2_max_desaturation_depth` (`max(0.0, baseline(t) - spo2(t))` over timestamp-matched pairs; **nullable**)
7. `spo2_time_below_90` (count of valid 1 Hz samples `< 90.0` in 60s window)
8. `spo2_time_below_95` (count of valid 1 Hz samples `< 95.0` in 60s window)
9. `hr_mean` (60s window mean, `BPM`)
10. `hr_min` (60s window min, `BPM`)
11. `hr_max` (60s window max, `BPM`)
12. `hr_std` (60s sample std `ddof=1`, `0.0` if `n == 1`)
13. `hr_range` (`hr_max - hr_min`)
14. `hr_slope` (`cov(rel_times, valid_hr) / var(rel_times)` over valid samples)
15. `hr_sampled_rmssd` (`sqrt(mean(diff^2))` over strictly adjacent 1s valid pairs; `NaN` if `< 20` pairs)
16. `hr_mean_absolute_diff` (`mean(|diff|)` over strictly adjacent 1s valid pairs; `NaN` if `< 20` pairs)

### 360-Second Causal Context & Quality Gate Parity
- **360s Causal Context:** Evaluating window `[w_start, w_end)` (`60s` at `1 Hz`) requires `[w_end - 360s, w_end)`:
  - `[w_end - 360s, w_start)` (`300s` prior history) warms up the causal rolling SpO2 baseline (`buffer_seconds=300`, `percentile=90.0`, `min_valid_samples=240`, `outage_gap_threshold=5s`, `warmup_streak_required=60s`).
  - `[w_start, w_end)` (`60s` evaluation window) supplies the 60 1-Hz grid slots for quality evaluation and feature computation.
- **Channel Quality Precedence (`quality_control.py`):**
  1. `EMPTY` (`valid_samples == 0`) → `REJECTED`
  2. `GAP_DOMINATED` (`longest_gap > 15s`) → `REJECTED`
  3. `INSUFFICIENT` (`valid_fraction < 0.50`) → `REJECTED`
  4. `PARTIAL_VALID` (`valid_fraction < 0.80` or `longest_gap > 5s`) → `DEGRADED`
  5. `VALID` (`valid_fraction >= 0.80` and `longest_gap <= 5s`) → `VALID`
- **Selected Quality Policy (`VALID`-Only Inference; `DEGRADED` Blocked):** Confirmed by Phase 7 training (`fit_final_model_c.py`, `train_model_c.py`) and Phase 8 `Gate P8-A` (`quality_control.py`, `model_service.py`, `test_gate_p8a.py` Req 2). Model C was trained and cross-validated **exclusively** on `combined_eligible_primary == True` (`VALID` only). `DEGRADED` (`PARTIAL_VALID`) windows are **blocked from inference** (`predict_proba` is never invoked) and persisted with `PredictionStatus.REJECTED`, `InputQualityStatus.DEGRADED` (preserving the original signal-quality classification separately from the decision not to run inference), `predictedClass = null`, `confidenceScore = null`, and a diagnostic `errorMessage` (see Section 12.1 & 12.4).
- **Physiological vs. Ingestion Bounds:** `IngestionService` accepts `spo2` in `[40, 100]` and `heartRate` in `[20, 250]`, whereas `signal_validation.py` sanitizes `spo2` outside `[50, 100]` and `hr` outside `[30, 220]` to `NaN` before quality gating. Stage 3 sidecar must preserve `signal_validation.py` sanitization for parity with Model C training.

---

## 4. Dependency, Environment & Path-Coupling Analysis

- **Python Runtime (`.venv`):** Python `3.14.10` with `fastapi==0.163.1`, `uvicorn==0.54.0`, `pydantic==2.14.3`, `scikit-learn==1.9.1`, `joblib==1.6.0`, `numpy==2.5.3`, `pandas==3.0.5`, `scipy==1.18.1`, `httpx==0.28.1`, `pytest==9.1.1`. Exact library version parity with `model_metadata.json` (`scikit-learn 1.9.1`, `joblib 1.6.0`, `numpy 2.5.3`, `pandas 3.0.5`) is already satisfied.
- **Prototype Path Dependencies to Eliminate:**
  - `feature_pipeline.py` / `config.py` reference `Sleep_Apnea_Dataset/` and `phase4_alignment/output/`.
  - `application/inference/model_service.py` uses `sys.path` injection pointing into `SomniSense/preprocessing/...`.
  - **Stage 3 Rule:** The new FastAPI sidecar inside `SomniSense_Team` (e.g., `ai-service/`) must be self-contained: copy the frozen `model_package` artifacts (`model_c_pipeline.joblib`, `feature_schema.json`, `model_metadata.json`, `inference.py`) read-only into `ai-service/artifacts/model_c/` (verifying SHA-256 at startup) and vendor clean, path-independent feature/quality/baseline modules with **zero** runtime imports from `C:\Users\LORD\Downloads\Minor_Project\SomniSense`.

---

## 5. Proposed FastAPI Internal Sidecar Architecture

- **Process Boundary:** Separate Python FastAPI process (`uvicorn`, bound to `127.0.0.1:8090` by default).
- **Network & Ownership Invariants:**
  - **Spring Boot** is the sole public API, PostgreSQL owner, and orchestrator.
  - **Python FastAPI** has **zero** PostgreSQL drivers/connections and **never** accepts direct browser/React traffic.
- **Endpoints:**
  1. `GET /internal/ai/health` — Liveness probe (no auth required or minimal status `{status: "UP", service: "somnisense-ai"}`).
  2. `GET /internal/ai/ready` — Readiness probe verifying `model_c_pipeline.joblib` is loaded, SHA-256 matches `faef00b0...`, pipeline steps (`imputer`, `scaler`, `classifier`) are intact, and warmup inference succeeded (`503` if not ready).
  3. `POST /internal/ai/evaluate-window` — Authenticated single-window causal feature extraction + Model C evaluation.

---

## 6. Request & Response Contract (`POST /internal/ai/evaluate-window`)

### Request Schema (Spring Boot → FastAPI)
```json
{
  "sessionId": "3cb7635e-e802-40c3-87dd-0f86c511cbd3",
  "windowStart": "2026-11-26T00:05:00Z",
  "windowEnd": "2026-11-26T00:06:00Z",
  "contextStart": "2026-11-26T00:00:00Z",
  "readings": [
    { "measuredAt": "2026-11-26T00:00:00Z", "spo2": 96.0, "heartRate": 64.0 }
  ]
}
```
- `windowEnd - windowStart` must equal `60s`.
- `contextStart` must be `windowEnd - 360s` (`300s` baseline context + `60s` evaluation window).
- `readings` are mapped onto a regular `1 Hz` grid `[contextStart, windowEnd)` (360 slots; missing seconds = `NaN`, duplicate timestamps resolved deterministically e.g. last-write or mean, out-of-range physiological values sanitized to `NaN`).

### Response Schemas (FastAPI → Spring Boot)

**1. Evaluated (`VALID` only):**
```json
{
  "sessionId": "3cb7635e-e802-40c3-87dd-0f86c511cbd3",
  "modelVersion": "phase7_model_c:1.0.0",
  "windowStart": "2026-11-26T00:05:00Z",
  "windowEnd": "2026-11-26T00:06:00Z",
  "predictionStatus": "NEEDS_REVIEW",
  "predictedClass": "APNEA_EVENT",
  "confidenceScore": 0.874210,
  "inputQualityStatus": "VALID",
  "decisionThreshold": 0.5,
  "processingTimeMs": 4,
  "errorMessage": null
}
```
- `predictedClass`: `"APNEA_EVENT"` if `confidenceScore >= 0.50` else `"NORMAL"`.
- `predictionStatus`: `"NEEDS_REVIEW"` for `"APNEA_EVENT"`, `"COMPLETED"` for `"NORMAL"`.

**2. Quality Rejected (`REJECTED`):**
```json
{
  "sessionId": "3cb7635e-e802-40c3-87dd-0f86c511cbd3",
  "modelVersion": "phase7_model_c:1.0.0",
  "windowStart": "2026-11-26T00:05:00Z",
  "windowEnd": "2026-11-26T00:06:00Z",
  "predictionStatus": "REJECTED",
  "predictedClass": null,
  "confidenceScore": null,
  "inputQualityStatus": "REJECTED",
  "decisionThreshold": 0.5,
  "processingTimeMs": 1,
  "errorMessage": "SPO2:REJECT_GAP_DOMINATED_22S"
}
```
- `predictedClass` and `confidenceScore` are strictly `null` (never fabricated).

**3. Processing Error (`400` / `500`):**
- Invalid request schema (`windowEnd - windowStart != 60s`, malformed timestamps) → HTTP `400`/`422` structured JSON error.
- Unexpected internal failure → HTTP `500` structured JSON error with correlation ID (no stack traces); Spring Boot maps timeout/5xx to `PredictionStatus.ERROR` when persisting failure outcomes.

---

## 7. Authentication, Security & Timeout Policy

- **Internal Shared-Key Auth:** `X-Internal-AI-Key` header validated using constant-time comparison (`hmac.compare_digest`) against `AI_SERVICE_API_KEY` environment variable (minimum 32 characters; fail-closed if unset). Missing/invalid key returns `401 Unauthorized`.
- **Correlation ID Propagation:** Forward `X-Correlation-Id` from Spring Boot to FastAPI logs and response headers.
- **No Sensitive Logging:** Log only `sessionId`, `windowStart`, `windowEnd`, `predictionStatus`, `inputQualityStatus`, `processingTimeMs`, and `correlationId`. Never log secrets or patient PII.
- **Spring Boot Client Timeouts:** `RestClient` / `HttpClient` configured with explicit connect timeout (e.g., `2s`) and read timeout (e.g., `5s`), bounded payload size, and retry-safe idempotency via Stage 2's `ux_predictions_session_model_window`.

---

## 8. Test Strategy & Parity Verification

1. **Startup & Artifact Integrity Tests:** Verify `model_c_pipeline.joblib` SHA-256 matches `faef00b0...`, `feature_schema.json` matches `c4d1b2e2...`, pipeline steps intact, and zero-fitting invariant holds.
2. **Feature Parity Tests:** Port/verify the 16-feature extractor and `StreamingRollingBaseline` against known golden vectors and `phase7_window_features.csv.gz` (`<= 1e-6` tolerance).
3. **Model Output Parity Tests:** Verify `POST /internal/ai/evaluate-window` probabilities match `ModelCInference.predict_observation` within `1e-9` and respect threshold `0.50`.
4. **Quality & Failure Tests:** Verify `EMPTY`, `GAP_DOMINATED (>15s)`, `INSUFFICIENT (<50%)` return `REJECTED` with `predictedClass: null`, `confidenceScore: null`; verify `401` on missing/bad `X-Internal-AI-Key`; verify `400`/`422` on invalid window boundaries.
5. **Spring Boot Integration Tests:** Mock/WireMock/TestServer tests in Java verifying Spring Boot client calls `/internal/ai/evaluate-window`, persists `COMPLETED`/`NEEDS_REVIEW`/`REJECTED`/`ERROR` via `PredictionService`, and handles sidecar timeouts safely.

---

## 9. Integration Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Timestamp alignment drift (sub-second `measuredAt` in PostgreSQL vs 1 Hz grid) | Snap/bucket readings onto `[contextStart, windowEnd)` using `round((measuredAt - contextStart).total_seconds())` with explicit bounds check. |
| `DEGRADED` window policy | **Resolved (`VALID`-only inference):** Block `DEGRADED` (`PARTIAL_VALID`) windows from Model C inference and persist as `REJECTED` (see Section 12.1). |
| Cold-start latency or missing artifact on startup | Run warmup inference in FastAPI lifespan startup; fail readiness (`/ready` -> `503`) if SHA-256 or pipeline check fails. |
| Prototype path coupling | Copy only frozen `model_package` artifacts into `SomniSense_Team/ai-service/` and prohibit imports from `SomniSense/`. |

---

## 10. Recommended Stage 3 Implementation Sequence

1. **Scaffold `ai-service/`** in `SomniSense_Team` with frozen `model_package` artifacts + SHA-256 startup verifier.
2. **Implement self-contained signal sanitization, 1 Hz grid builder, 300s causal baseline, quality classifier, and 16-feature extractor**, plus parity tests (`<= 1e-6`).
3. **Implement FastAPI endpoints** (`/internal/ai/health`, `/internal/ai/ready`, `/internal/ai/evaluate-window`) with `X-Internal-AI-Key` auth and structured error handling.
4. **Implement Spring Boot internal AI client & orchestration service** calling FastAPI and persisting results via Stage 2 `PredictionService`.
5. **Run full automated Python (`pytest`) + Java (`mvnw test` on `somnisense_test_db`) suites**, document contracts, and publish.

---

## 11. Stage 3 Acceptance Criteria

- [x] Frozen `model_c_pipeline.joblib` SHA-256 (`faef00b0...`) and `feature_schema.json` SHA-256 (`c4d1b2e2...`) verified at startup; zero `fit()`/`fit_transform()` calls.
- [x] `VALID`-only inference policy enforced: `DEGRADED` (`PARTIAL_VALID`) windows are blocked from `predict_proba` and persisted as `PredictionStatus.REJECTED` + `InputQualityStatus.DEGRADED`; `REJECTED` windows are persisted as `PredictionStatus.REJECTED` + `InputQualityStatus.REJECTED`, both with `predictedClass = null`, `confidenceScore = null`, and exact quality-gate `errorMessage`.
- [x] Deterministic reading order (`measuredAt ASC, receivedAt ASC, id ASC`) from Spring Boot and 1 Hz causal bucketization (`[contextStart, windowEnd)`, `floor` bucketing, `(measuredAt ASC, receivedAt ASC, id ASC, input_index ASC)` tie-breaking, `np.nan` for missing seconds, `[50, 100]` / `[30, 220]` sanitization) match Phase 8 parity within `1e-6` for features and `1e-9` for `predict_proba`; threshold fixed at `0.50`.
- [x] `POST /internal/ai/evaluate-window` enforces `X-Internal-AI-Key`, consumes 360s causal context (`300s` baseline + `60s` window), and returns `COMPLETED`/`NEEDS_REVIEW`/`REJECTED` without fabricated values.
- [x] Spring Boot remains the sole public API and PostgreSQL owner; Python has zero DB connections; React does not call FastAPI directly.
- [x] All Python (`pytest`: 7/7) and Java (`mvnw test` on `somnisense_test_db`: 181/181) tests pass with 0 failures.
- [x] Flyway `V1`–`V5`, Stage 2 APIs, Model C artifacts, original `SomniSense` prototype, and Stage 1 frontend remain intact.

---

## 12. Supplementary Technical Verification (2026-11-26)

### 12.1 Resolution of the `DEGRADED` Quality Policy

1. **Why Phase 7 `feature_pipeline.py` Computed Features for `PARTIAL_VALID` / `DEGRADED`:**
   - `feature_pipeline.py` (lines 318, 326, 336) passed `eligible_sensitivity` (`VALID` or `PARTIAL_VALID`) so offline sensitivity-analysis columns (`eligibility_sensitivity_A`, `eligibility_sensitivity_B`) could be populated in `phase7_window_features.csv.gz`.
2. **Why Model C and Phase 8 Restricted Inference to `VALID` Only:**
   - Both `train_model_c.py` (lines 153–158) and `fit_final_model_c.py` (lines 103–108) filtered the training and 5-fold CV cohort strictly with `combined_eligible_primary == True` (`spo2_state == "VALID"` and `hr_state == "VALID"`, i.e., `>= 80%` valid coverage and `longest_gap <= 5s`, yielding `N = 96,659` windows across 50 patients).
   - `application/processing/quality_control.py` (lines 177–183) states the authoritative runtime invariant: *"a baseline Model C prediction is permitted only when combined quality is VALID and combined_eligible_primary is True."*
   - `application/inference/model_service.py` (lines 80–85) and `application/tests/test_gate_p8a.py` (`test_02_partial_valid_normalized_to_degraded_and_inference_blocked`) explicitly block `DEGRADED` (`PARTIAL_VALID`) windows from `ModelCInference`.
3. **Impact on Sensitivity / Specificity:**
   - All published Model C performance metrics (`AUROC = 0.8500`, `AUPRC = 0.8176`, `Sensitivity = 0.7283`, `Specificity = 0.8473` in `PHASE7_GATE5_MODEL_C_TRAINING_REPORT.md`) were measured **exclusively** on `VALID` windows. Model C has **no** validated sensitivity/specificity on `DEGRADED` windows (`50%–79%` coverage or `6s–15s` gaps), where missing samples alter `spo2_time_below_90`, `spo2_time_below_95`, `spo2_min`, `hr_sampled_rmssd`, and `hr_mean_absolute_diff`.
4. **Stage 2 `PredictionService` Contract Correction (Preserving Signal Quality Separately):**
   - Stage 2 `PredictionService.java` previously required `inputQualityStatus == REJECTED` whenever `predictionStatus == REJECTED`.
   - **Approved Mapping:**
     - `VALID` → `PredictionStatus.COMPLETED` or `NEEDS_REVIEW`, `InputQualityStatus.VALID`
     - `DEGRADED` → `PredictionStatus.REJECTED`, `InputQualityStatus.DEGRADED` (`predictedClass = null`, `confidenceScore = null`, `errorMessage` explains quality-gate reason, `predict_proba` never invoked)
     - `REJECTED` → `PredictionStatus.REJECTED`, `InputQualityStatus.REJECTED` (`predictedClass = null`, `confidenceScore = null`, `errorMessage` explains quality-gate reason, `predict_proba` never invoked)
   - **Backward-Compatible Update:** `PredictionService.validateStatusPayload` now permits `InputQualityStatus.DEGRADED` or `InputQualityStatus.REJECTED` (defaulting `null` to `REJECTED`) when `predictionStatus == REJECTED`, while continuing to reject `InputQualityStatus.VALID` on `REJECTED` and continuing to forbid non-null `predictedClass`/`confidenceScore` on `REJECTED`/`ERROR`.

### 12.2 Read-Only Execution of Phase 8 Parity & Integration Tests

Executed against `C:\Users\LORD\Downloads\Minor_Project\SomniSense` using `SomniSense\.venv\Scripts\python.exe` (`Python 3.14.10`, `pytest 9.1.1`) with `-p no:cacheprovider` (zero files written to the preserved workspace):

- `application/tests/test_feature_parity.py`: **2/2 passed** (`test_feature_extraction_parity_patient_01` within `1e-6`, `test_feature_vector_schema_and_types`).
- `application/tests/test_model_integration.py`: **3/3 passed** (`test_model_metadata_and_whitelisted_features`, `test_prediction_output_and_thresholding`, `test_model_pipeline_immutability`).
- **Combined Result:** **5 passed, 0 failed, 0 skipped in 5.70s**.

### 12.3 Finalized 1 Hz Timestamp, Ordering & Sanitization Contract

Derived directly from `application/backend/services/session_service.py` (lines 433–629), `application/processing/signal_validation.py` (lines 13–59), `SensorReading.java`, and `preprocessing/phase7_sliding_window/feature_pipeline.py` (lines 193–219):

1. **Spring-to-FastAPI Reading-Order Contract:**
   - `SensorReading` exposes `id` (`UUID`), `measuredAt` (`Instant`, UTC), `receivedAt` (`Instant`, UTC), `spo2` (`BigDecimal`), and `heartRate` (`BigDecimal`).
   - `SensorReadingRepository.findWindowOrdered(sessionId, contextStart, windowEnd)` queries `measuredAt >= contextStart AND measuredAt < windowEnd` with `ORDER BY r.measuredAt ASC, r.receivedAt ASC, r.id ASC`.
   - Spring Boot transmits `readings` in this deterministic order, including `id`, `measuredAt`, `receivedAt`, `spo2`, and `heartRate`.
   - Python FastAPI **additionally** sorts incoming `readings` by `(measuredAt ASC, receivedAt ASC, id ASC, input_index ASC)` so it never relies on unspecified database ordering.
2. **Exact 1 Hz Bucket Boundaries:**
   - The 360-second causal context `[contextStart, windowEnd)` is partitioned into 360 contiguous 1-second half-open buckets `i ∈ {0, ..., 359}`, where bucket `i` covers `[contextStart + i·1s, contextStart + (i + 1)·1s)`.
   - Buckets `0..299` (`[contextStart, windowStart)`) warm up `StreamingRollingBaseline`; buckets `300..359` (`[windowStart, windowEnd)`) form the 60-second evaluation window (`t_avail = windowEnd`).
3. **Sub-Second Timestamps & Duplicate Readings:**
   - Each reading with `measuredAt ∈ [contextStart, windowEnd)` maps to bucket index `idx = math.floor((measuredAt - contextStart).total_seconds())`. Readings outside `[contextStart, windowEnd)` are excluded.
   - If multiple readings fall into the same 1 Hz bucket `i`, deterministic last-write-wins (after `(measuredAt ASC, receivedAt ASC, id ASC, input_index ASC)` sorting) populates bucket `i`.
4. **Missing Seconds & Out-of-Range Sanitization:**
   - Any bucket `i` with no reading remains `np.nan` — never zero-filled, interpolated, or forward-filled.
   - Preserves `signal_validation.py`: finite `spo2 ∈ [50.0, 100.0]` and finite `heartRate ∈ [30.0, 220.0]`; values outside these physiological bounds become `None` → `np.nan` prior to quality gating and feature extraction.
