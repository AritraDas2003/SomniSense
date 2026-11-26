"""Internal FastAPI application for the standalone SomniSense S1-S3 simulator worker."""

from __future__ import annotations

import hmac
import logging
import uuid
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.config import MIN_KEY_LENGTH, Settings, load_settings
from app.schemas import (
    InternalErrorResponse,
    WorkerControlResponse,
    WorkerHealthResponse,
    WorkerStartRequest,
    WorkerStatusResponse,
    WorkerStopRequest,
)
from app.worker_manager import BackendIngestionClient, WorkerManager

logger = logging.getLogger("somnisense.simulator")
logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")


def create_app(
    settings_override: Optional[Settings] = None,
    ingestion_client_override: Optional[BackendIngestionClient] = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        cfg = settings_override or load_settings()
        app.state.settings = cfg
        app.state.manager = WorkerManager(cfg, ingestion_client=ingestion_client_override)
        logger.info("Initialized simulator-worker instance=%s (zero DB drivers)", cfg.worker_instance_id)
        yield
        await app.state.manager.shutdown_all()
        logger.info("Simulator-worker instance=%s shut down cleanly", cfg.worker_instance_id)

    app = FastAPI(title="SomniSense Standalone Simulator Worker", version="1.0.0", lifespan=lifespan)

    @app.middleware("http")
    async def correlation_id_middleware(request: Request, call_next):
        cid = request.headers.get("X-Correlation-Id") or str(uuid.uuid4())
        request.state.correlation_id = cid
        response = await call_next(request)
        response.headers["X-Correlation-Id"] = cid
        return response

    def require_internal_sim_key(
        request: Request,
        x_internal_sim_key: Optional[str] = Header(default=None, alias="X-Internal-Sim-Key"),
    ) -> None:
        cfg: Settings = request.app.state.settings
        if not cfg.internal_key or len(cfg.internal_key) < MIN_KEY_LENGTH:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Internal simulator authentication is not configured",
            )
        if not x_internal_sim_key or not hmac.compare_digest(x_internal_sim_key, cfg.internal_key):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid internal simulator key",
            )

    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException):
        cid = getattr(request.state, "correlation_id", str(uuid.uuid4()))
        code = "UNAUTHORIZED" if exc.status_code == 401 else "SIM_WORKER_ERROR"
        body = InternalErrorResponse(code=code, message=str(exc.detail), correlationId=cid)
        return JSONResponse(status_code=exc.status_code, content=body.model_dump(), headers={"X-Correlation-Id": cid})

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError):
        cid = getattr(request.state, "correlation_id", str(uuid.uuid4()))
        body = InternalErrorResponse(code="INVALID_REQUEST", message="Request payload validation failed", correlationId=cid)
        return JSONResponse(status_code=400, content=body.model_dump(), headers={"X-Correlation-Id": cid})

    @app.exception_handler(ValueError)
    async def value_error_handler(request: Request, exc: ValueError):
        cid = getattr(request.state, "correlation_id", str(uuid.uuid4()))
        body = InternalErrorResponse(code="INVALID_REQUEST", message=str(exc), correlationId=cid)
        return JSONResponse(status_code=400, content=body.model_dump(), headers={"X-Correlation-Id": cid})

    @app.get("/internal/sim/health", response_model=WorkerHealthResponse)
    async def health(request: Request):
        cfg: Settings = request.app.state.settings
        mgr: WorkerManager = request.app.state.manager
        return WorkerHealthResponse(
            status="UP",
            workerInstanceId=cfg.worker_instance_id,
            activeRuns=mgr.active_count,
            databaseAccess=False,
        )

    @app.get("/internal/sim/ready", response_model=WorkerHealthResponse)
    async def ready(request: Request):
        cfg: Settings = request.app.state.settings
        mgr: WorkerManager = request.app.state.manager
        if len(cfg.internal_key) < MIN_KEY_LENGTH or len(cfg.ingestion_api_key) < MIN_KEY_LENGTH:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Worker internal/ingestion keys must be at least 32 characters",
            )
        return WorkerHealthResponse(
            status="READY",
            workerInstanceId=cfg.worker_instance_id,
            activeRuns=mgr.active_count,
            databaseAccess=False,
        )

    @app.post(
        "/internal/sim/start",
        response_model=WorkerControlResponse,
        dependencies=[Depends(require_internal_sim_key)],
    )
    async def start_simulation(body: WorkerStartRequest, request: Request):
        mgr: WorkerManager = request.app.state.manager
        return await mgr.start_or_resume(body, is_resume=False)

    @app.post(
        "/internal/sim/resume",
        response_model=WorkerControlResponse,
        dependencies=[Depends(require_internal_sim_key)],
    )
    async def resume_simulation(body: WorkerStartRequest, request: Request):
        mgr: WorkerManager = request.app.state.manager
        return await mgr.start_or_resume(body, is_resume=True)

    @app.post(
        "/internal/sim/pause",
        response_model=WorkerControlResponse,
        dependencies=[Depends(require_internal_sim_key)],
    )
    async def pause_simulation(body: WorkerStopRequest, request: Request):
        mgr: WorkerManager = request.app.state.manager
        return await mgr.pause(body)

    @app.post(
        "/internal/sim/stop",
        response_model=WorkerControlResponse,
        dependencies=[Depends(require_internal_sim_key)],
    )
    async def stop_simulation(body: WorkerStopRequest, request: Request):
        mgr: WorkerManager = request.app.state.manager
        return await mgr.stop(body)

    @app.get(
        "/internal/sim/status",
        response_model=WorkerStatusResponse,
        dependencies=[Depends(require_internal_sim_key)],
    )
    async def get_status(request: Request, sessionId: Optional[uuid.UUID] = Query(default=None)):
        cfg: Settings = request.app.state.settings
        mgr: WorkerManager = request.app.state.manager
        runs = mgr.status_snapshots(session_id=sessionId)
        return WorkerStatusResponse(
            workerInstanceId=cfg.worker_instance_id,
            activeRuns=mgr.active_count,
            runs=runs,
        )

    return app


app = create_app()
