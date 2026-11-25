"""Internal FastAPI application for SomniSense Stage 3 Model C inference."""

from __future__ import annotations

import hmac
import logging
import uuid
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.config import MIN_API_KEY_LENGTH, MODEL_VERSION_CONTRACT, Settings, load_settings
from app.model_engine import ModelCEngine
from app.schemas import (
    EvaluateWindowRequest,
    EvaluateWindowResponse,
    HealthResponse,
    InternalErrorResponse,
    ReadyResponse,
)

logger = logging.getLogger("somnisense.ai")
logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")


def create_app(settings_override: Optional[Settings] = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        cfg = settings_override or load_settings()
        app.state.settings = cfg
        app.state.engine = None
        app.state.startup_error = None
        try:
            app.state.engine = ModelCEngine(cfg)
            logger.info("Loaded frozen Model C (%s) sha256=%s", MODEL_VERSION_CONTRACT, app.state.engine.model_sha256)
        except Exception as exc:
            app.state.startup_error = str(exc)
            logger.error("Model C startup failed: %s", exc)
        yield

    app = FastAPI(title="SomniSense Internal AI Sidecar", version="1.0.0", lifespan=lifespan)

    @app.middleware("http")
    async def correlation_id_middleware(request: Request, call_next):
        cid = request.headers.get("X-Correlation-Id") or str(uuid.uuid4())
        request.state.correlation_id = cid
        response = await call_next(request)
        response.headers["X-Correlation-Id"] = cid
        return response

    def require_internal_key(
        request: Request,
        x_internal_ai_key: Optional[str] = Header(default=None, alias="X-Internal-AI-Key"),
    ) -> None:
        cfg: Settings = request.app.state.settings
        if not cfg.api_key or len(cfg.api_key) < MIN_API_KEY_LENGTH:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Internal AI authentication is not configured")
        if not x_internal_ai_key or not hmac.compare_digest(x_internal_ai_key, cfg.api_key):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid internal AI key")

    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException):
        cid = getattr(request.state, "correlation_id", str(uuid.uuid4()))
        code = "UNAUTHORIZED" if exc.status_code == 401 else "INTERNAL_AI_ERROR"
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

    @app.exception_handler(Exception)
    async def unexpected_exception_handler(request: Request, exc: Exception):
        cid = getattr(request.state, "correlation_id", str(uuid.uuid4()))
        logger.exception("Unhandled AI sidecar error [cid=%s]", cid)
        body = InternalErrorResponse(code="INTERNAL_ERROR", message="Unexpected AI sidecar error", correlationId=cid)
        return JSONResponse(status_code=500, content=body.model_dump(), headers={"X-Correlation-Id": cid})

    @app.get("/internal/ai/health", response_model=HealthResponse)
    async def health():
        return HealthResponse(status="UP", service="somnisense-ai")

    @app.get("/internal/ai/ready", response_model=ReadyResponse)
    async def ready(request: Request):
        engine: Optional[ModelCEngine] = request.app.state.engine
        cfg: Settings = request.app.state.settings
        auth_ok = bool(cfg.api_key and len(cfg.api_key) >= MIN_API_KEY_LENGTH)
        if engine is None or not engine.ready or not auth_ok:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="AI sidecar is not ready",
            )
        return ReadyResponse(
            status="READY",
            modelVersion=MODEL_VERSION_CONTRACT,
            modelSha256=engine.model_sha256,
            schemaSha256=engine.schema_sha256,
            authConfigured=auth_ok,
        )

    @app.post("/internal/ai/evaluate-window", response_model=EvaluateWindowResponse, dependencies=[Depends(require_internal_key)])
    async def evaluate_window(payload: EvaluateWindowRequest, request: Request):
        engine: Optional[ModelCEngine] = request.app.state.engine
        if engine is None or not engine.ready:
            raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Model C engine is not ready")

        cid = getattr(request.state, "correlation_id", "")
        result = engine.evaluate_window(
            session_id=payload.session_id,
            window_start=payload.window_start,
            window_end=payload.window_end,
            context_start=payload.context_start,
            readings=payload.readings,
        )
        logger.info(
            "Evaluated window session=%s status=%s quality=%s time_ms=%s cid=%s",
            payload.session_id,
            result["predictionStatus"],
            result["inputQualityStatus"],
            result["processingTimeMs"],
            cid,
        )
        return EvaluateWindowResponse(**result)

    return app


app = create_app()
