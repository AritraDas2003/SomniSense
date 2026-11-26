"""
Async simulator run coordinator handling lifecycle, fencing termination, lease keepalive,
bounded batching, and retry/backpressure toward Spring Boot /api/v1/ingestion/readings.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Dict, List, Optional, Tuple

import httpx

from app.config import MIN_KEY_LENGTH, Settings
from app.engine.core import SimulatedReading, SimulationEngine
from app.schemas import RunStatusSnapshot, WorkerControlResponse, WorkerStartRequest, WorkerStopRequest

logger = logging.getLogger("somnisense.simulator.manager")


def format_reading_key(device_code: str, session_code: str, step_number: int) -> str:
    return f"{device_code.strip().upper()}-{session_code.strip().upper()}-{step_number:08d}"


def format_utc_iso(ts: datetime) -> str:
    utc_ts = ts.astimezone(timezone.utc) if ts.tzinfo else ts.replace(tzinfo=timezone.utc)
    return utc_ts.isoformat().replace("+00:00", "Z")


@dataclass(frozen=True)
class IngestionPostResult:
    status_code: int
    accepted: int = 0
    duplicates: int = 0
    rejected_count: int = 0
    error_code: Optional[str] = None
    error_message: Optional[str] = None


class BackendIngestionClient:
    """Async HTTP client targeting Spring Boot POST /api/v1/ingestion/readings."""

    def __init__(
        self,
        ingestion_api_key: str,
        timeout_seconds: float = 5.0,
        transport: Optional[httpx.AsyncBaseTransport] = None,
    ) -> None:
        self._api_key = ingestion_api_key
        self._timeout = timeout_seconds
        self._transport = transport

    async def post_readings(
        self,
        base_url: str,
        payload: Dict[str, Any],
        correlation_id: Optional[str] = None,
    ) -> IngestionPostResult:
        if len(self._api_key) < MIN_KEY_LENGTH:
            return IngestionPostResult(
                status_code=401,
                error_code="INGESTION_KEY_UNCONFIGURED",
                error_message="Simulator ingestion API key is not configured",
            )
        url = f"{base_url.rstrip('/')}/api/v1/ingestion/readings"
        headers = {
            "Content-Type": "application/json",
            "X-Ingestion-Key": self._api_key,
            "X-Correlation-Id": correlation_id or str(uuid.uuid4()),
        }
        client_kwargs: Dict[str, Any] = {"timeout": self._timeout}
        if self._transport is not None:
            client_kwargs["transport"] = self._transport

        async with httpx.AsyncClient(**client_kwargs) as client:
            resp = await client.post(url, json=payload, headers=headers)

        data: Dict[str, Any] = {}
        try:
            parsed = resp.json()
            if isinstance(parsed, dict):
                data = parsed
        except Exception:
            data = {}

        if 200 <= resp.status_code < 300:
            return IngestionPostResult(
                status_code=resp.status_code,
                accepted=int(data.get("accepted", 0)),
                duplicates=int(data.get("duplicates", 0)),
                rejected_count=len(data.get("rejected", []) or []),
            )
        return IngestionPostResult(
            status_code=resp.status_code,
            error_code=str(data.get("code") or f"HTTP_{resp.status_code}"),
            error_message=str(data.get("message") or f"Backend returned HTTP {resp.status_code}"),
        )


@dataclass
class ManagedRun:
    simulation_run_id: uuid.UUID
    session_id: uuid.UUID
    session_code: str
    device_code: str
    scenario_id: str
    worker_generation: int
    status: str
    last_submitted_step: int
    total_accepted: int = 0
    total_duplicates: int = 0
    last_error: Optional[str] = None
    task: Optional[asyncio.Task[None]] = None
    stop_event: asyncio.Event = field(default_factory=asyncio.Event)

    def snapshot(self) -> RunStatusSnapshot:
        return RunStatusSnapshot(
            simulationRunId=self.simulation_run_id,
            sessionId=self.session_id,
            sessionCode=self.session_code,
            deviceCode=self.device_code,
            scenarioId=self.scenario_id,
            workerGeneration=self.worker_generation,
            status=self.status,
            lastSubmittedStep=self.last_submitted_step,
            totalAccepted=self.total_accepted,
            totalDuplicates=self.total_duplicates,
            lastError=self.last_error,
        )


class WorkerManager:
    """Lifecycle manager for active simulated sessions."""

    def __init__(
        self,
        settings: Settings,
        ingestion_client: Optional[BackendIngestionClient] = None,
        clock_utc: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    ) -> None:
        self._settings = settings
        self._client = ingestion_client or BackendIngestionClient(
            settings.ingestion_api_key, timeout_seconds=settings.http_timeout_seconds
        )
        self._clock_utc = clock_utc
        self._runs: Dict[uuid.UUID, ManagedRun] = {}
        self._lock = asyncio.Lock()

    @property
    def active_count(self) -> int:
        return sum(1 for r in self._runs.values() if r.status == "RUNNING")

    async def start_or_resume(self, cmd: WorkerStartRequest, *, is_resume: bool = False) -> WorkerControlResponse:
        # Validate scenario, preset, and engine params upfront before launching task
        engine = SimulationEngine(
            scenario_id=cmd.scenarioId,
            seed=cmd.seed,
            cadence_hz=cmd.cadenceHz,
            playback_speed=cmd.playbackSpeed,
            quality_preset=cmd.qualityPreset,
        )
        if cmd.startStep > 1:
            engine.fast_forward_before_step(cmd.startStep)

        async with self._lock:
            existing = self._runs.get(cmd.sessionId)
            if existing is not None:
                if cmd.workerGeneration < existing.worker_generation:
                    raise ValueError(
                        f"Stale workerGeneration {cmd.workerGeneration} < current {existing.worker_generation}"
                    )
                if existing.task is not None and not existing.task.done():
                    existing.stop_event.set()
                    existing.task.cancel()
                    try:
                        await existing.task
                    except asyncio.CancelledError:
                        pass

            run = ManagedRun(
                simulation_run_id=cmd.simulationRunId,
                session_id=cmd.sessionId,
                session_code=cmd.sessionCode,
                device_code=cmd.deviceCode,
                scenario_id=cmd.scenarioId,
                worker_generation=cmd.workerGeneration,
                status="RUNNING",
                last_submitted_step=max(0, cmd.startStep - 1),
            )
            self._runs[cmd.sessionId] = run
            run.task = asyncio.create_task(
                self._emit_loop(run, engine, cmd),
                name=f"sim-{cmd.sessionCode}-gen{cmd.workerGeneration}",
            )
            action = "Resumed" if is_resume else "Started"
            logger.info(
                "%s simulation session=%s run=%s gen=%d startStep=%d scenario=%s",
                action,
                cmd.sessionId,
                cmd.simulationRunId,
                cmd.workerGeneration,
                cmd.startStep,
                cmd.scenarioId,
            )
            return WorkerControlResponse(
                simulationRunId=run.simulation_run_id,
                sessionId=run.session_id,
                workerInstanceId=self._settings.worker_instance_id,
                status=run.status,
                lastSubmittedStep=run.last_submitted_step,
            )

    async def pause(self, cmd: WorkerStopRequest) -> WorkerControlResponse:
        return await self._halt(cmd, target_status="PAUSED")

    async def stop(self, cmd: WorkerStopRequest) -> WorkerControlResponse:
        return await self._halt(cmd, target_status="STOPPED")

    async def _halt(self, cmd: WorkerStopRequest, target_status: str) -> WorkerControlResponse:
        async with self._lock:
            run = self._runs.get(cmd.sessionId)
            if run is None:
                return WorkerControlResponse(
                    simulationRunId=cmd.simulationRunId or uuid.UUID(int=0),
                    sessionId=cmd.sessionId,
                    workerInstanceId=self._settings.worker_instance_id,
                    status=target_status,
                    lastSubmittedStep=0,
                )
            if cmd.workerGeneration is not None and run.worker_generation > cmd.workerGeneration:
                # Newer generation owns this session; do not kill the newer worker.
                return WorkerControlResponse(
                    simulationRunId=run.simulation_run_id,
                    sessionId=run.session_id,
                    workerInstanceId=self._settings.worker_instance_id,
                    status=run.status,
                    lastSubmittedStep=run.last_submitted_step,
                )
            run.status = target_status
            run.stop_event.set()
            task = run.task
            if task is not None and not task.done():
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass
            return WorkerControlResponse(
                simulationRunId=run.simulation_run_id,
                sessionId=run.session_id,
                workerInstanceId=self._settings.worker_instance_id,
                status=run.status,
                lastSubmittedStep=run.last_submitted_step,
            )

    async def shutdown_all(self) -> None:
        async with self._lock:
            tasks: List[asyncio.Task[None]] = []
            for run in self._runs.values():
                if run.status == "RUNNING":
                    run.status = "STOPPED"
                run.stop_event.set()
                if run.task is not None and not run.task.done():
                    run.task.cancel()
                    tasks.append(run.task)
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)

    def status_snapshots(self, session_id: Optional[uuid.UUID] = None) -> Dict[str, RunStatusSnapshot]:
        items: Dict[str, RunStatusSnapshot] = {}
        for sid, run in self._runs.items():
            if session_id is None or sid == session_id:
                items[str(sid)] = run.snapshot()
        return items

    async def _emit_loop(self, run: ManagedRun, engine: SimulationEngine, cmd: WorkerStartRequest) -> None:
        step = cmd.startStep
        dt_seconds = 1.0 / cmd.cadenceHz
        playback = cmd.playbackSpeed
        batch_size = min(self._settings.batch_size, 600)
        flush_interval = self._settings.flush_interval_seconds
        base_url = (cmd.ingestionBaseUrl or self._settings.backend_base_url).rstrip("/")

        pending: List[Tuple[int, Dict[str, Any]]] = []
        last_flush_mono = time.monotonic()
        last_lease_ok_mono = time.monotonic()
        step_zero_anchor = cmd.sessionStartedAt.astimezone(timezone.utc) if cmd.sessionStartedAt.tzinfo else cmd.sessionStartedAt.replace(tzinfo=timezone.utc)
        run_wall_start_mono = time.monotonic()
        steps_in_this_run = 0

        try:
            while not run.stop_event.is_set() and self._is_current(run):
                measured_at = step_zero_anchor + timedelta(seconds=(step - 1) * dt_seconds)
                now_utc = self._clock_utc()

                # Pace according to playback speed while never drifting past backend clock-skew guard (10s).
                if playback <= 1.0:
                    ahead_seconds = (measured_at - now_utc).total_seconds()
                    if ahead_seconds > 0.0:
                        await asyncio.sleep(ahead_seconds / playback)
                else:
                    target_elapsed = steps_in_this_run * (dt_seconds / playback)
                    wait_wall = target_elapsed - (time.monotonic() - run_wall_start_mono)
                    # Also enforce safe lead margin (<= 5s) vs Spring Boot 10s MEASURED_AT_IN_FUTURE guard.
                    lead_seconds = (measured_at - self._clock_utc()).total_seconds()
                    if lead_seconds > 5.0:
                        wait_wall = max(wait_wall, lead_seconds - 5.0)
                    if wait_wall > 0.0:
                        await asyncio.sleep(wait_wall)

                if run.stop_event.is_set() or not self._is_current(run):
                    break

                sample: SimulatedReading = engine.generate_step(step)
                item = {
                    "readingKey": format_reading_key(cmd.deviceCode, cmd.sessionCode, sample.step_number),
                    "measuredAt": format_utc_iso(measured_at),
                    "spo2": sample.spo2,
                    "heartRate": sample.heart_rate,
                    "signalQuality": None,
                }
                pending.append((sample.step_number, item))
                step += 1
                steps_in_this_run += 1

                # Keep pending queue strictly bounded under persistent failure / backpressure
                if len(pending) > self._settings.max_pending_steps:
                    run.status = "FAILED_BACKPRESSURE"
                    run.last_error = "Pending simulator buffer exceeded capacity"
                    break

                elapsed_since_flush = time.monotonic() - last_flush_mono
                next_measured = step_zero_anchor + timedelta(seconds=(step - 1) * dt_seconds)
                next_wait_est = max(0.0, (next_measured - self._clock_utc()).total_seconds() / playback)
                lease_half_life = max(0.5, cmd.leaseDurationSeconds * 0.4)

                should_flush = (
                    len(pending) >= batch_size
                    or elapsed_since_flush >= flush_interval
                    or (elapsed_since_flush + next_wait_est) >= flush_interval
                    or (time.monotonic() - last_lease_ok_mono) >= lease_half_life
                )
                if should_flush:
                    ok, terminal_status, err_msg, lease_renewed_mono = await self._flush_with_retry(
                        run, cmd, base_url, pending, last_lease_ok_mono
                    )
                    if not ok:
                        if self._is_current(run):
                            run.status = terminal_status
                            run.last_error = err_msg
                        break
                    pending.clear()
                    last_flush_mono = time.monotonic()
                    last_lease_ok_mono = lease_renewed_mono
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.exception("Simulator loop aborted for session=%s gen=%d: %s", run.session_id, run.worker_generation, exc)
            if self._is_current(run):
                run.status = "ERROR"
                run.last_error = str(exc)

    async def _flush_with_retry(
        self,
        run: ManagedRun,
        cmd: WorkerStartRequest,
        base_url: str,
        pending: List[Tuple[int, Dict[str, Any]]],
        last_lease_ok_mono: float,
    ) -> Tuple[bool, str, Optional[str], float]:
        payload = {
            "deviceCode": cmd.deviceCode,
            "sessionCode": cmd.sessionCode,
            "simulationRunId": str(cmd.simulationRunId),
            "workerGeneration": cmd.workerGeneration,
            "workerInstanceId": self._settings.worker_instance_id,
            "readings": [entry[1] for entry in pending],
        }
        cid = str(uuid.uuid4())
        max_attempts = 1 + self._settings.max_ingest_retries

        for attempt in range(1, max_attempts + 1):
            if run.stop_event.is_set() or not self._is_current(run):
                return False, run.status, None, last_lease_ok_mono

            if (time.monotonic() - last_lease_ok_mono) >= float(cmd.leaseDurationSeconds):
                msg = "Backend worker lease window elapsed before flush succeeded"
                logger.warning("Stopping session=%s gen=%d: %s", run.session_id, run.worker_generation, msg)
                return False, "LEASE_EXPIRED", msg, last_lease_ok_mono

            try:
                res = await self._client.post_readings(base_url, payload, correlation_id=cid)
                if 200 <= res.status_code < 300:
                    run.total_accepted += res.accepted
                    run.total_duplicates += res.duplicates
                    run.last_submitted_step = max(run.last_submitted_step, pending[-1][0])
                    return True, "RUNNING", None, time.monotonic()

                if res.status_code in (401, 403, 404, 409):
                    # Fencing / auth / missing session -> immediate self-termination, zero retries!
                    msg = f"Ingestion rejected with HTTP {res.status_code} ({res.error_code}): {res.error_message}"
                    logger.warning("Orphan/fencing stop for session=%s gen=%d: %s [cid=%s]",
                                   run.session_id, run.worker_generation, msg, cid)
                    return False, "FENCED_STOPPED", msg, last_lease_ok_mono

                if 400 <= res.status_code < 500 and res.status_code != 429:
                    msg = f"Non-retryable ingestion error HTTP {res.status_code} ({res.error_code})"
                    logger.error("Stopping session=%s gen=%d: %s [cid=%s]", run.session_id, run.worker_generation, msg, cid)
                    return False, "ERROR", msg, last_lease_ok_mono

                err_desc = f"HTTP {res.status_code} ({res.error_code})"
            except (httpx.TimeoutException, httpx.RequestError) as exc:
                err_desc = exc.__class__.__name__

            if attempt >= max_attempts:
                msg = f"Ingestion failed after {attempt} attempts: {err_desc}"
                logger.warning("Backpressure exhaustion for session=%s gen=%d: %s [cid=%s]",
                               run.session_id, run.worker_generation, msg, cid)
                return False, "FAILED_BACKPRESSURE", msg, last_lease_ok_mono

            delay = min(5.0, 0.5 * (2 ** (attempt - 1)), max(0.1, float(cmd.leaseDurationSeconds) * 0.25))
            await asyncio.sleep(delay)

        return False, "FAILED_BACKPRESSURE", "Unexpected retry termination", last_lease_ok_mono

    def _is_current(self, run: ManagedRun) -> bool:
        current = self._runs.get(run.session_id)
        return current is run
