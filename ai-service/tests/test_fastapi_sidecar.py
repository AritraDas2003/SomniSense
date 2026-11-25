"""FastAPI endpoint, auth, VALID-only gating, and error-handling tests."""

from __future__ import annotations

import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

AI_SERVICE_DIR = Path(__file__).resolve().parents[1]
if str(AI_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(AI_SERVICE_DIR))

from app.config import Settings
from app.main import create_app

API_KEY = "test-internal-ai-key-0123456789abcdef-0123456789"


def _make_client(api_key: str = API_KEY) -> TestClient:
    cfg = Settings(
        api_key=api_key,
        artifact_dir=AI_SERVICE_DIR / "artifacts" / "model_c",
        host="127.0.0.1",
        port=8090,
    )
    return TestClient(create_app(settings_override=cfg))


def _readings_payload(ctx_start: datetime, spo2_fn, hr_fn, skip_indices=None):
    skip = set(skip_indices or [])
    items = []
    for i in range(360):
        if i in skip:
            continue
        t = ctx_start + timedelta(seconds=i)
        items.append({
            "id": f"rd-{i:03d}",
            "measuredAt": t.isoformat().replace("+00:00", "Z"),
            "receivedAt": (t + timedelta(milliseconds=250)).isoformat().replace("+00:00", "Z"),
            "spo2": spo2_fn(i),
            "heartRate": hr_fn(i),
        })
    return items


def test_health_ready_and_auth_enforcement():
    with _make_client() as client:
        assert client.get("/internal/ai/health").status_code == 200
        ready = client.get("/internal/ai/ready")
        assert ready.status_code == 200
        assert ready.json()["status"] == "READY"
        assert ready.json()["authConfigured"] is True

        ctx = datetime(2026, 11, 26, 0, 0, 0, tzinfo=timezone.utc)
        body = {
            "sessionId": "ses-1",
            "contextStart": ctx.isoformat().replace("+00:00", "Z"),
            "windowStart": (ctx + timedelta(seconds=300)).isoformat().replace("+00:00", "Z"),
            "windowEnd": (ctx + timedelta(seconds=360)).isoformat().replace("+00:00", "Z"),
            "readings": [],
        }
        assert client.post("/internal/ai/evaluate-window", json=body).status_code == 401
        assert client.post(
            "/internal/ai/evaluate-window",
            json=body,
            headers={"X-Internal-AI-Key": "wrong-key"},
        ).status_code == 401

    with _make_client(api_key="") as unconfigured:
        assert unconfigured.get("/internal/ai/ready").status_code == 503


def test_evaluate_window_normal_and_apnea_valid_windows():
    ctx = datetime(2026, 11, 26, 0, 0, 0, tzinfo=timezone.utc)
    win_start = ctx + timedelta(seconds=300)
    win_end = ctx + timedelta(seconds=360)

    with _make_client() as client:
        # Normal window
        normal_resp = client.post(
            "/internal/ai/evaluate-window",
            headers={"X-Internal-AI-Key": API_KEY, "X-Correlation-Id": "cid-norm-1"},
            json={
                "sessionId": "ses-norm",
                "contextStart": ctx.isoformat().replace("+00:00", "Z"),
                "windowStart": win_start.isoformat().replace("+00:00", "Z"),
                "windowEnd": win_end.isoformat().replace("+00:00", "Z"),
                "readings": _readings_payload(ctx, lambda i: 97.0, lambda i: 60.0 + (i % 2)),
            },
        )
        assert normal_resp.status_code == 200
        assert normal_resp.headers["X-Correlation-Id"] == "cid-norm-1"
        n_json = normal_resp.json()
        assert n_json["predictionStatus"] == "COMPLETED"
        assert n_json["predictedClass"] == "NORMAL"
        assert n_json["inputQualityStatus"] == "VALID"
        assert 0.0 <= n_json["confidenceScore"] < 0.50

        # Realistic smooth desaturation dip -> NEEDS_REVIEW + APNEA_EVENT
        import math
        apnea_resp = client.post(
            "/internal/ai/evaluate-window",
            headers={"X-Internal-AI-Key": API_KEY},
            json={
                "sessionId": "ses-apnea",
                "contextStart": ctx.isoformat().replace("+00:00", "Z"),
                "windowStart": win_start.isoformat().replace("+00:00", "Z"),
                "windowEnd": win_end.isoformat().replace("+00:00", "Z"),
                "readings": _readings_payload(
                    ctx,
                    lambda i: 96.0 if i < 300 else round(94.0 - 12.0 * math.sin(math.pi * (i - 300) / 59.0), 2),
                    lambda i: 64.0 if i < 300 else round(62.0 + 18.0 * math.sin(math.pi * (i - 300) / 59.0), 2),
                ),
            },
        )
        assert apnea_resp.status_code == 200
        a_json = apnea_resp.json()
        assert a_json["predictionStatus"] == "NEEDS_REVIEW"
        assert a_json["predictedClass"] == "APNEA_EVENT"
        assert a_json["inputQualityStatus"] == "VALID"
        assert 0.50 <= a_json["confidenceScore"] <= 1.0


def test_degraded_and_rejected_windows_never_reach_predict_proba():
    ctx = datetime(2026, 11, 26, 0, 0, 0, tzinfo=timezone.utc)
    win_start = ctx + timedelta(seconds=300)
    win_end = ctx + timedelta(seconds=360)

    with _make_client() as client:
        engine = client.app.state.engine
        with patch.object(engine.inference, "predict_observation", wraps=engine.inference.predict_observation) as spy:
            # 1. DEGRADED: 8-second gap inside the 60s window (>5s, <=15s; 52/60 = 86.7% valid) -> PARTIAL_VALID -> DEGRADED
            degraded_resp = client.post(
                "/internal/ai/evaluate-window",
                headers={"X-Internal-AI-Key": API_KEY},
                json={
                    "sessionId": "ses-deg",
                    "contextStart": ctx.isoformat().replace("+00:00", "Z"),
                    "windowStart": win_start.isoformat().replace("+00:00", "Z"),
                    "windowEnd": win_end.isoformat().replace("+00:00", "Z"),
                    "readings": _readings_payload(ctx, lambda i: 96.0, lambda i: 65.0, skip_indices=range(310, 318)),
                },
            )
            assert degraded_resp.status_code == 200
            d_json = degraded_resp.json()
            assert d_json["predictionStatus"] == "REJECTED"
            assert d_json["inputQualityStatus"] == "DEGRADED"
            assert d_json["predictedClass"] is None
            assert d_json["confidenceScore"] is None
            assert "SENSITIVITY_ONLY" in d_json["errorMessage"]

            # 2. REJECTED: 20-second gap (>15s) -> GAP_DOMINATED -> REJECTED
            rejected_resp = client.post(
                "/internal/ai/evaluate-window",
                headers={"X-Internal-AI-Key": API_KEY},
                json={
                    "sessionId": "ses-rej",
                    "contextStart": ctx.isoformat().replace("+00:00", "Z"),
                    "windowStart": win_start.isoformat().replace("+00:00", "Z"),
                    "windowEnd": win_end.isoformat().replace("+00:00", "Z"),
                    "readings": _readings_payload(ctx, lambda i: 96.0, lambda i: 65.0, skip_indices=range(310, 330)),
                },
            )
            assert rejected_resp.status_code == 200
            r_json = rejected_resp.json()
            assert r_json["predictionStatus"] == "REJECTED"
            assert r_json["inputQualityStatus"] == "REJECTED"
            assert r_json["predictedClass"] is None
            assert r_json["confidenceScore"] is None
            assert "REJECT_GAP_DOMINATED" in r_json["errorMessage"]

            assert spy.call_count == 0


def test_invalid_window_boundaries_return_400():
    ctx = datetime(2026, 11, 26, 0, 0, 0, tzinfo=timezone.utc)
    with _make_client() as client:
        bad = client.post(
            "/internal/ai/evaluate-window",
            headers={"X-Internal-AI-Key": API_KEY},
            json={
                "sessionId": "ses-bad",
                "contextStart": ctx.isoformat().replace("+00:00", "Z"),
                "windowStart": (ctx + timedelta(seconds=310)).isoformat().replace("+00:00", "Z"),
                "windowEnd": (ctx + timedelta(seconds=360)).isoformat().replace("+00:00", "Z"),
                "readings": [],
            },
        )
        assert bad.status_code == 400
        assert bad.json()["code"] == "INVALID_REQUEST"
