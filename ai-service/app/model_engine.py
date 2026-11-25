"""
Frozen Model C inference wrapper with SHA-256 verification and VALID-only gate enforcement.
"""

from __future__ import annotations

import hashlib
import importlib.util
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable

import numpy as np

from app.config import MODEL_VERSION_CONTRACT, Settings
from app.signal_processing import (
    APPROVED_16_PREDICTORS,
    BASELINE_BUFFER_SECONDS,
    CAUSAL_CONTEXT_SECONDS,
    StreamingRollingBaseline,
    WINDOW_DURATION_SECONDS,
    build_1hz_causal_grid,
    evaluate_channel_quality,
    evaluate_combined_channel_quality,
    extract_16_features,
)


def compute_sha256(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest().lower()


def _load_frozen_inference_module(inference_path: Path):
    spec = importlib.util.spec_from_file_location("somnisense_frozen_model_c_inference", str(inference_path))
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load frozen inference module from {inference_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ModelCEngine:
    """Loads frozen Model C artifact, verifies SHA-256 hashes, and evaluates 360s causal windows."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.model_path = settings.artifact_dir / "model_c_pipeline.joblib"
        self.schema_path = settings.artifact_dir / "feature_schema.json"
        self.inference_path = settings.artifact_dir / "inference.py"

        for req in (self.model_path, self.schema_path, self.inference_path):
            if not req.exists():
                raise FileNotFoundError(f"Required frozen Model C artifact missing: {req}")

        self.model_sha256 = compute_sha256(self.model_path)
        if self.model_sha256 != settings.expected_model_sha256:
            raise RuntimeError(
                f"Model C artifact SHA-256 mismatch: expected {settings.expected_model_sha256}, got {self.model_sha256}"
            )

        self.schema_sha256 = compute_sha256(self.schema_path)
        if self.schema_sha256 != settings.expected_schema_sha256:
            raise RuntimeError(
                f"Feature schema SHA-256 mismatch: expected {settings.expected_schema_sha256}, got {self.schema_sha256}"
            )

        frozen_mod = _load_frozen_inference_module(self.inference_path)
        if list(frozen_mod.APPROVED_16_PREDICTORS) != APPROVED_16_PREDICTORS:
            raise RuntimeError("Approved 16-predictor schema mismatch against frozen inference.py")

        self.inference = frozen_mod.ModelCInference(
            model_path=str(self.model_path),
            decision_threshold=0.50,
        )
        # Warmup inference (deterministic, non-fitting)
        dummy = {feat: 95.0 for feat in APPROVED_16_PREDICTORS}
        _ = self.inference.predict_observation(dummy, availability_timestamp=0.0)
        self.ready = True

    def evaluate_window(
        self,
        session_id: str,
        window_start: datetime,
        window_end: datetime,
        context_start: datetime,
        readings: Iterable[Any],
    ) -> Dict[str, Any]:
        t0 = time.perf_counter()

        win_delta = (window_end - window_start).total_seconds()
        if round(win_delta, 6) != float(WINDOW_DURATION_SECONDS):
            raise ValueError(f"Evaluation window must be exactly {WINDOW_DURATION_SECONDS}s, got {win_delta}s")

        ctx_delta = (window_end - context_start).total_seconds()
        if round(ctx_delta, 6) != float(CAUSAL_CONTEXT_SECONDS):
            raise ValueError(f"Causal context must be exactly {CAUSAL_CONTEXT_SECONDS}s, got {ctx_delta}s")

        spo2_360, hr_360, ts_360 = build_1hz_causal_grid(context_start, window_end, readings)

        baseline_tracker = StreamingRollingBaseline(buffer_seconds=BASELINE_BUFFER_SECONDS)
        baseline_360 = np.array(
            [baseline_tracker.update(None if np.isnan(v) else float(v)) for v in spo2_360],
            dtype=np.float64,
        )

        w_spo2 = spo2_360[BASELINE_BUFFER_SECONDS:]
        w_hr = hr_360[BASELINE_BUFFER_SECONDS:]
        w_base = baseline_360[BASELINE_BUFFER_SECONDS:]
        w_times = ts_360[BASELINE_BUFFER_SECONDS:]

        spo2_q = evaluate_channel_quality(w_spo2, expected_samples=WINDOW_DURATION_SECONDS)
        hr_q = evaluate_channel_quality(w_hr, expected_samples=WINDOW_DURATION_SECONDS)
        comb_q = evaluate_combined_channel_quality(spo2_q, hr_q)

        quality_state = comb_q["combined_quality_state"]
        is_primary = bool(comb_q["combined_eligible_primary"])

        # Strict VALID-only policy: DEGRADED and REJECTED never reach predict_proba.
        if quality_state != "VALID" or not is_primary:
            elapsed_ms = max(0, int(round((time.perf_counter() - t0) * 1000.0)))
            preserved_quality = "DEGRADED" if quality_state == "DEGRADED" else "REJECTED"
            return {
                "sessionId": session_id,
                "modelVersion": MODEL_VERSION_CONTRACT,
                "windowStart": window_start,
                "windowEnd": window_end,
                "predictionStatus": "REJECTED",
                "predictedClass": None,
                "confidenceScore": None,
                "inputQualityStatus": preserved_quality,
                "decisionThreshold": 0.50,
                "processingTimeMs": elapsed_ms,
                "errorMessage": comb_q["rejection_reason_combined"],
            }

        features = extract_16_features(
            spo2_window=w_spo2,
            hr_window=w_hr,
            baseline_window=w_base,
            timestamps_window=w_times,
            eligible=True,
        )
        res = self.inference.predict_observation(
            observation=features,
            availability_timestamp=window_end.timestamp(),
        )
        prob = round(float(res["probability"]), 6)
        pred = int(res["prediction"])
        elapsed_ms = max(0, int(round((time.perf_counter() - t0) * 1000.0)))

        return {
            "sessionId": session_id,
            "modelVersion": MODEL_VERSION_CONTRACT,
            "windowStart": window_start,
            "windowEnd": window_end,
            "predictionStatus": "NEEDS_REVIEW" if pred == 1 else "COMPLETED",
            "predictedClass": "APNEA_EVENT" if pred == 1 else "NORMAL",
            "confidenceScore": prob,
            "inputQualityStatus": "VALID",
            "decisionThreshold": 0.50,
            "processingTimeMs": elapsed_ms,
            "errorMessage": None,
        }
