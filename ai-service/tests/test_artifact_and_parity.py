"""Artifact integrity, zero-fitting, 16-feature parity, and 1 Hz timestamp contract tests."""

from __future__ import annotations

import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

AI_SERVICE_DIR = Path(__file__).resolve().parents[1]
if str(AI_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(AI_SERVICE_DIR))

from app.config import EXPECTED_MODEL_SHA256, EXPECTED_SCHEMA_SHA256, Settings
from app.model_engine import ModelCEngine, compute_sha256
from app.signal_processing import (
    APPROVED_16_PREDICTORS,
    build_1hz_causal_grid,
    extract_16_features,
    validate_hr_reading,
    validate_spo2_reading,
)


def _settings() -> Settings:
    return Settings(
        api_key="test-internal-ai-key-0123456789abcdef-0123456789",
        artifact_dir=AI_SERVICE_DIR / "artifacts" / "model_c",
        host="127.0.0.1",
        port=8090,
    )


def test_frozen_artifact_hashes_and_zero_fitting():
    cfg = _settings()
    assert compute_sha256(cfg.artifact_dir / "model_c_pipeline.joblib") == EXPECTED_MODEL_SHA256
    assert compute_sha256(cfg.artifact_dir / "feature_schema.json") == EXPECTED_SCHEMA_SHA256

    engine = ModelCEngine(cfg)
    pipe = engine.inference.pipeline
    coef_before = pipe.named_steps["classifier"].coef_.copy()
    intercept_before = pipe.named_steps["classifier"].intercept_.copy()
    mean_before = pipe.named_steps["scaler"].mean_.copy()
    stats_before = pipe.named_steps["imputer"].statistics_.copy()

    t0 = datetime(2026, 11, 26, 0, 0, 0, tzinfo=timezone.utc)
    readings = [
        SimpleNamespace(
            id=f"r-{i}",
            measured_at=t0 + timedelta(seconds=i),
            received_at=t0 + timedelta(seconds=i, milliseconds=200),
            spo2=97.0,
            heart_rate=64.0,
        )
        for i in range(360)
    ]
    res = engine.evaluate_window(
        session_id="ses-1",
        window_start=t0 + timedelta(seconds=300),
        window_end=t0 + timedelta(seconds=360),
        context_start=t0,
        readings=readings,
    )
    assert res["predictionStatus"] == "COMPLETED"
    assert res["predictedClass"] == "NORMAL"
    assert res["confidenceScore"] < 0.50

    np.testing.assert_array_equal(pipe.named_steps["classifier"].coef_, coef_before)
    np.testing.assert_array_equal(pipe.named_steps["classifier"].intercept_, intercept_before)
    np.testing.assert_array_equal(pipe.named_steps["scaler"].mean_, mean_before)
    np.testing.assert_array_equal(pipe.named_steps["imputer"].statistics_, stats_before)


def test_16_feature_schema_ordering_and_probability_parity():
    engine = ModelCEngine(_settings())
    spo2 = np.array([86.0 if i % 2 == 0 else 88.0 for i in range(60)], dtype=np.float64)
    hr = np.array([72.0 + (i % 5) for i in range(60)], dtype=np.float64)
    base = np.full(60, 96.0, dtype=np.float64)
    times = np.arange(60.0, dtype=np.float64)

    feats = extract_16_features(spo2, hr, base, times, eligible=True)
    assert list(feats.keys()) == APPROVED_16_PREDICTORS

    direct_vec = np.array([feats[k] for k in APPROVED_16_PREDICTORS], dtype=np.float64).reshape(1, -1)
    expected_prob = float(engine.inference.pipeline.predict_proba(direct_vec)[0, 1])
    res = engine.inference.predict_observation(feats, availability_timestamp=360.0)
    assert abs(float(res["probability"]) - expected_prob) < 1e-9
    assert res["decision_threshold"] == 0.50


def test_1hz_timestamp_bucketing_duplicates_subsecond_and_sanitization():
    ctx_start = datetime(2026, 11, 26, 0, 0, 0, tzinfo=timezone.utc)
    win_end = ctx_start + timedelta(seconds=360)

    # Unsorted readings, sub-second timestamps, duplicate bucket with tie-breaker, out-of-range values
    readings = [
        SimpleNamespace(
            id="b-later",
            measured_at=ctx_start + timedelta(seconds=10, milliseconds=800),
            received_at=ctx_start + timedelta(seconds=11),
            spo2=95.0,
            heart_rate=70.0,
        ),
        SimpleNamespace(
            id="a-earlier",
            measured_at=ctx_start + timedelta(seconds=10, milliseconds=200),
            received_at=ctx_start + timedelta(seconds=11),
            spo2=99.0,
            heart_rate=60.0,
        ),
        # Out-of-range physiological values (accepted by ingestion [40,100]/[20,250] but sanitized here)
        SimpleNamespace(
            id="c-oor",
            measured_at=ctx_start + timedelta(seconds=11),
            received_at=ctx_start + timedelta(seconds=12),
            spo2=45.0,
            heart_rate=240.0,
        ),
        # Outside [context_start, window_end) -> excluded
        SimpleNamespace(
            id="d-out",
            measured_at=win_end,
            received_at=win_end,
            spo2=96.0,
            heart_rate=65.0,
        ),
    ]

    spo2_arr, hr_arr, _ = build_1hz_causal_grid(ctx_start, win_end, readings)
    assert spo2_arr.shape == (360,)
    assert spo2_arr[10] == 95.0  # deterministic last-write-wins at bucket 10
    assert hr_arr[10] == 70.0
    assert np.isnan(spo2_arr[11])  # 45.0 sanitized to NaN
    assert np.isnan(hr_arr[11])    # 240.0 sanitized to NaN
    assert np.isnan(spo2_arr[0])   # missing second remains NaN

    assert validate_spo2_reading(49.9) is None
    assert validate_spo2_reading(50.0) == 50.0
    assert validate_hr_reading(29.9) is None
    assert validate_hr_reading(30.0) == 30.0
