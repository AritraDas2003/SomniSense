"""Tests for FastAPI simulator worker lifecycle, fencing, batching, retries, and lease guards."""

from __future__ import annotations

import asyncio
import time
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List

import httpx
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.schemas import WorkerStartRequest, WorkerStopRequest
from app.worker_manager import BackendIngestionClient, IngestionPostResult, WorkerManager

INTERNAL_KEY = "test-internal-sim-key-0123456789abcdef-0123456789"
INGESTION_KEY = "test-ingestion-key-0123456789abcdef-0123456789"


def make_settings(**overrides: Any) -> Settings:
    defaults = dict(
        internal_key=INTERNAL_KEY,
        ingestion_api_key=INGESTION_KEY,
        backend_base_url="http://127.0.0.1:8080",
        worker_instance_id="sim-worker-test-1",
        batch_size=3,
        flush_interval_seconds=0.15,
        http_timeout_seconds=1.0,
        max_ingest_retries=3,
        max_pending_steps=50,
    )
    defaults.update(overrides)
    return Settings(**defaults)


class RecordingIngestionClient(BackendIngestionClient):
    """Fake backend ingestion endpoint recording exact batches & simulating status/network responses."""

    def __init__(self, script=None) -> None:
        super().__init__(INGESTION_KEY, timeout_seconds=1.0)
        self.calls: List[Dict[str, Any]] = []
        self._script = script

    async def post_readings(self, base_url: str, payload: Dict[str, Any], correlation_id=None) -> IngestionPostResult:
        self.calls.append(payload)
        if self._script is not None:
            outcome = self._script(len(self.calls), payload)
            if isinstance(outcome, Exception):
                raise outcome
            return outcome
        return IngestionPostResult(status_code=200, accepted=len(payload["readings"]), duplicates=0)


def test_health_ready_and_internal_key_auth():
    unconfigured = create_app(settings_override=make_settings(internal_key="short", ingestion_api_key="short"))
    with TestClient(unconfigured) as client:
        h = client.get("/internal/sim/health")
        assert h.status_code == 200
        assert h.json()["databaseAccess"] is False
        assert client.get("/internal/sim/ready").status_code == 503
        assert client.get("/internal/sim/status").status_code == 401

    app = create_app(settings_override=make_settings())
    with TestClient(app) as client:
        assert client.get("/internal/sim/ready").status_code == 200
        assert client.get("/internal/sim/status").status_code == 401
        assert client.get("/internal/sim/status", headers={"X-Internal-Sim-Key": "wrong" * 10}).status_code == 401
        ok = client.get("/internal/sim/status", headers={"X-Internal-Sim-Key": INTERNAL_KEY, "X-Correlation-Id": "cid-42"})
        assert ok.status_code == 200
        assert ok.headers["X-Correlation-Id"] == "cid-42"


def test_start_pause_resume_continuity_and_older_gen_protection():
    async def scenario():
        recorder = RecordingIngestionClient()
        mgr = WorkerManager(make_settings(batch_size=3, flush_interval_seconds=0.1), ingestion_client=recorder)
        run_id = uuid.uuid4()
        sess_id = uuid.uuid4()
        # Session started 40s ago -> plenty of deterministic catch-up steps to test pause before exhaustion
        started_at = datetime.fromtimestamp(time.time() - 40.0, tz=timezone.utc)

        start_cmd = WorkerStartRequest(
            simulationRunId=run_id,
            sessionId=sess_id,
            sessionCode="SES-4C-01",
            deviceCode="DEV-SIM-01",
            scenarioId="normal_sleep",
            seed=99,
            cadenceHz=1.0,
            playbackSpeed=1.0,
            qualityPreset=None,
            workerGeneration=1,
            startStep=1,
            sessionStartedAt=started_at,
            leaseDurationSeconds=30,
        )
        ctrl = await mgr.start_or_resume(start_cmd, is_resume=False)
        assert ctrl.status == "RUNNING"
        for _ in range(40):
            if len(recorder.calls) >= 2:
                break
            await asyncio.sleep(0.02)

        paused = await mgr.pause(WorkerStopRequest(sessionId=sess_id, simulationRunId=run_id, workerGeneration=1))
        assert paused.status == "PAUSED"
        assert len(recorder.calls) >= 1

        emitted_keys = [r["readingKey"] for c in recorder.calls for r in c["readings"]]
        # Verify fencing metadata, null signalQuality, and strictly 1-based contiguous key format
        first_batch = recorder.calls[0]
        assert first_batch["simulationRunId"] == str(run_id)
        assert first_batch["workerGeneration"] == 1
        assert first_batch["workerInstanceId"] == "sim-worker-test-1"
        assert all(r["signalQuality"] is None for r in first_batch["readings"])
        assert emitted_keys[0] == "DEV-SIM-01-SES-4C-01-00000001"
        assert len(emitted_keys) == len(set(emitted_keys))

        resume_step = paused.lastSubmittedStep + 1
        resume_cmd = start_cmd.model_copy(update={"workerGeneration": 2, "startStep": resume_step, "playbackSpeed": 5.0})
        resumed = await mgr.start_or_resume(resume_cmd, is_resume=True)
        assert resumed.status == "RUNNING"

        # Stale /pause command from generation 1 must NOT halt generation 2!
        stale_pause = await mgr.pause(WorkerStopRequest(sessionId=sess_id, simulationRunId=run_id, workerGeneration=1))
        assert stale_pause.status == "RUNNING"

        for _ in range(40):
            if any(c["workerGeneration"] == 2 for c in recorder.calls):
                break
            await asyncio.sleep(0.02)
        await mgr.stop(WorkerStopRequest(sessionId=sess_id, simulationRunId=run_id, workerGeneration=2))
        await mgr.shutdown_all()

        gen2_batches = [c for c in recorder.calls if c["workerGeneration"] == 2]
        assert len(gen2_batches) >= 1
        gen2_first_key = gen2_batches[0]["readings"][0]["readingKey"]
        assert gen2_first_key == f"DEV-SIM-01-SES-4C-01-{resume_step:08d}"

        # Exact contiguous sequence across pause/resume (no duplicates, no skipped steps)
        all_steps = [int(r["readingKey"].split("-")[-1]) for c in recorder.calls for r in c["readings"]]
        assert all_steps == list(range(1, len(all_steps) + 1))

    asyncio.run(scenario())


def test_http_control_endpoints_drive_lifecycle_and_graceful_shutdown():
    recorder = RecordingIngestionClient()
    app = create_app(
        settings_override=make_settings(batch_size=2, flush_interval_seconds=0.1),
        ingestion_client_override=recorder,
    )
    run_id = str(uuid.uuid4())
    sess_id = str(uuid.uuid4())
    headers = {"X-Internal-Sim-Key": INTERNAL_KEY}
    started_at = datetime.fromtimestamp(time.time() - 8.0, tz=timezone.utc).isoformat()

    with TestClient(app) as client:
        start_resp = client.post(
            "/internal/sim/start",
            headers=headers,
            json={
                "simulationRunId": run_id,
                "sessionId": sess_id,
                "sessionCode": "SES-HTTP",
                "deviceCode": "DEV-HTTP",
                "scenarioId": "severe_osa",
                "seed": 55,
                "cadenceHz": 1.0,
                "playbackSpeed": 5.0,
                "qualityPreset": "CLEAN",
                "workerGeneration": 1,
                "startStep": 1,
                "sessionStartedAt": started_at,
                "leaseDurationSeconds": 30,
            },
        )
        assert start_resp.status_code == 200
        assert start_resp.json()["status"] == "RUNNING"
        time.sleep(0.25)

        pause_resp = client.post(
            "/internal/sim/pause",
            headers=headers,
            json={"sessionId": sess_id, "simulationRunId": run_id, "workerGeneration": 1},
        )
        assert pause_resp.status_code == 200
        assert pause_resp.json()["status"] == "PAUSED"
        paused_step = pause_resp.json()["lastSubmittedStep"]

        resume_resp = client.post(
            "/internal/sim/resume",
            headers=headers,
            json={
                "simulationRunId": run_id,
                "sessionId": sess_id,
                "sessionCode": "SES-HTTP",
                "deviceCode": "DEV-HTTP",
                "scenarioId": "severe_osa",
                "seed": 55,
                "cadenceHz": 1.0,
                "playbackSpeed": 5.0,
                "qualityPreset": "CLEAN",
                "workerGeneration": 2,
                "startStep": paused_step + 1,
                "sessionStartedAt": started_at,
                "leaseDurationSeconds": 30,
            },
        )
        assert resume_resp.status_code == 200
        assert resume_resp.json()["status"] == "RUNNING"
        time.sleep(0.15)

        stop_resp = client.post(
            "/internal/sim/stop",
            headers=headers,
            json={"sessionId": sess_id, "simulationRunId": run_id, "workerGeneration": 2},
        )
        assert stop_resp.status_code == 200
        assert stop_resp.json()["status"] == "STOPPED"
    # Lifespan exit verifies graceful cancellation/cleanup
    assert app.state.manager.active_count == 0


def test_stale_generation_409_terminates_immediately_without_retries():
    async def scenario():
        def reject_409(attempt, payload):
            return IngestionPostResult(status_code=409, error_code="STALE_WORKER_GENERATION", error_message="stale gen")

        recorder = RecordingIngestionClient(script=reject_409)
        mgr = WorkerManager(make_settings(batch_size=2, flush_interval_seconds=0.1), ingestion_client=recorder)
        sess_id = uuid.uuid4()
        cmd = WorkerStartRequest(
            simulationRunId=uuid.uuid4(),
            sessionId=sess_id,
            sessionCode="SES-409",
            deviceCode="DEV-409",
            scenarioId="normal_sleep",
            seed=11,
            cadenceHz=1.0,
            playbackSpeed=10.0,
            workerGeneration=1,
            startStep=1,
            sessionStartedAt=datetime.fromtimestamp(time.time() - 10.0, tz=timezone.utc),
            leaseDurationSeconds=30,
        )
        await mgr.start_or_resume(cmd)
        await asyncio.sleep(0.3)

        snap = mgr.status_snapshots(sess_id)[str(sess_id)]
        assert snap.status == "FENCED_STOPPED"
        assert snap.lastSubmittedStep == 0
        # Must NOT retry after 409!
        assert len(recorder.calls) == 1
        await mgr.shutdown_all()

    asyncio.run(scenario())


def test_network_retry_preserves_batch_order_and_backpressure_limits():
    async def scenario():
        # Attempt 1 fails with ReadTimeout, Attempt 2 returns 503, Attempt 3 succeeds!
        def scripted(call_idx, payload):
            if call_idx == 1:
                return httpx.ReadTimeout("timeout")
            if call_idx == 2:
                return IngestionPostResult(status_code=503, error_code="UNAVAILABLE")
            return IngestionPostResult(status_code=200, accepted=len(payload["readings"]), duplicates=0)

        recorder = RecordingIngestionClient(script=scripted)
        mgr = WorkerManager(make_settings(batch_size=2, flush_interval_seconds=0.1, max_ingest_retries=3), ingestion_client=recorder)
        sess_id = uuid.uuid4()
        cmd = WorkerStartRequest(
            simulationRunId=uuid.uuid4(),
            sessionId=sess_id,
            sessionCode="SES-RTY",
            deviceCode="DEV-RTY",
            scenarioId="normal_sleep",
            seed=12,
            cadenceHz=1.0,
            playbackSpeed=5.0,
            workerGeneration=1,
            startStep=1,
            sessionStartedAt=datetime.fromtimestamp(time.time() - 10.0, tz=timezone.utc),
            leaseDurationSeconds=30,
        )
        await mgr.start_or_resume(cmd)
        await asyncio.sleep(2.0)
        await mgr.stop(WorkerStopRequest(sessionId=sess_id))

        # All 3 calls must carry the exact same batch of readingKeys starting at 00000001
        assert len(recorder.calls) >= 3
        keys_1 = [r["readingKey"] for r in recorder.calls[0]["readings"]]
        keys_2 = [r["readingKey"] for r in recorder.calls[1]["readings"]]
        keys_3 = [r["readingKey"] for r in recorder.calls[2]["readings"]]
        assert keys_1 == keys_2 == keys_3
        assert keys_1 == ["DEV-RTY-SES-RTY-00000001", "DEV-RTY-SES-RTY-00000002"]

        snap = mgr.status_snapshots(sess_id)[str(sess_id)]
        assert snap.lastSubmittedStep >= 2
        await mgr.shutdown_all()

    asyncio.run(scenario())


def test_lease_expiration_halts_worker_when_backend_unreachable():
    async def scenario():
        def always_down(call_idx, payload):
            return httpx.ConnectError("backend down")

        recorder = RecordingIngestionClient(script=always_down)
        mgr = WorkerManager(make_settings(batch_size=2, flush_interval_seconds=0.1, max_ingest_retries=5), ingestion_client=recorder)
        sess_id = uuid.uuid4()
        cmd = WorkerStartRequest(
            simulationRunId=uuid.uuid4(),
            sessionId=sess_id,
            sessionCode="SES-LSE",
            deviceCode="DEV-LSE",
            scenarioId="normal_sleep",
            seed=13,
            cadenceHz=1.0,
            playbackSpeed=10.0,
            workerGeneration=1,
            startStep=1,
            sessionStartedAt=datetime.fromtimestamp(time.time() - 5.0, tz=timezone.utc),
            leaseDurationSeconds=1,
        )
        await mgr.start_or_resume(cmd)
        await asyncio.sleep(1.5)
        snap = mgr.status_snapshots(sess_id)[str(sess_id)]
        assert snap.status in ("LEASE_EXPIRED", "FAILED_BACKPRESSURE")
        assert snap.lastSubmittedStep == 0
        await mgr.shutdown_all()

    asyncio.run(scenario())
