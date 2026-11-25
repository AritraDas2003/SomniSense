"""Pydantic v2 request and response schemas for the internal Model C FastAPI sidecar."""

from __future__ import annotations

from datetime import datetime
from typing import List, Literal, Optional
from pydantic import BaseModel, ConfigDict, Field, field_validator


class ReadingInput(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: Optional[str] = None
    measured_at: datetime = Field(alias="measuredAt")
    received_at: Optional[datetime] = Field(default=None, alias="receivedAt")
    spo2: Optional[float] = None
    heart_rate: Optional[float] = Field(default=None, alias="heartRate")

    @field_validator("measured_at", "received_at")
    @classmethod
    def ensure_timezone_aware(cls, v: Optional[datetime]) -> Optional[datetime]:
        if v is not None and v.tzinfo is None:
            raise ValueError("Timestamp must include UTC timezone offset")
        return v


class EvaluateWindowRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    session_id: str = Field(alias="sessionId", min_length=1, max_length=64)
    window_start: datetime = Field(alias="windowStart")
    window_end: datetime = Field(alias="windowEnd")
    context_start: datetime = Field(alias="contextStart")
    readings: List[ReadingInput] = Field(default_factory=list, max_length=5000)

    @field_validator("window_start", "window_end", "context_start")
    @classmethod
    def ensure_utc_aware(cls, v: datetime) -> datetime:
        if v.tzinfo is None:
            raise ValueError("Timestamp must include UTC timezone offset")
        return v


class EvaluateWindowResponse(BaseModel):
    sessionId: str
    modelVersion: str
    windowStart: datetime
    windowEnd: datetime
    predictionStatus: Literal["COMPLETED", "NEEDS_REVIEW", "REJECTED"]
    predictedClass: Optional[Literal["APNEA_EVENT", "NORMAL"]] = None
    confidenceScore: Optional[float] = None
    inputQualityStatus: Literal["VALID", "DEGRADED", "REJECTED"]
    decisionThreshold: float
    processingTimeMs: int
    errorMessage: Optional[str] = None


class HealthResponse(BaseModel):
    status: str
    service: str


class ReadyResponse(BaseModel):
    status: str
    modelVersion: str
    modelSha256: str
    schemaSha256: str
    authConfigured: bool


class InternalErrorResponse(BaseModel):
    code: str
    message: str
    correlationId: str
