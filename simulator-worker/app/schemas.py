"""Pydantic DTO contracts matching Spring Boot SimulationWorkerClient."""

from __future__ import annotations

from datetime import datetime
from typing import Dict, Optional
from uuid import UUID
from pydantic import BaseModel, Field, field_validator


class WorkerStartRequest(BaseModel):
    simulationRunId: UUID
    sessionId: UUID
    sessionCode: str = Field(..., min_length=1, max_length=40)
    deviceCode: str = Field(..., min_length=1, max_length=50)
    scenarioId: str = Field(default="normal_sleep", min_length=1, max_length=60)
    seed: int = Field(default=42, ge=0)
    cadenceHz: float = Field(default=1.0, gt=0.0, le=10.0)
    playbackSpeed: float = Field(default=1.0, gt=0.0, le=60.0)
    qualityPreset: Optional[str] = Field(default=None, max_length=60)
    workerGeneration: int = Field(..., ge=1)
    startStep: int = Field(..., ge=1)
    sessionStartedAt: datetime
    leaseDurationSeconds: int = Field(default=30, ge=1)
    ingestionBaseUrl: Optional[str] = None

    @field_validator("sessionCode", "deviceCode", "scenarioId")
    @classmethod
    def strip_text(cls, v: str) -> str:
        return v.strip()


class WorkerStopRequest(BaseModel):
    sessionId: UUID
    simulationRunId: Optional[UUID] = None
    workerGeneration: Optional[int] = None


class WorkerControlResponse(BaseModel):
    simulationRunId: UUID
    sessionId: UUID
    workerInstanceId: str
    status: str
    lastSubmittedStep: int


class RunStatusSnapshot(BaseModel):
    simulationRunId: UUID
    sessionId: UUID
    sessionCode: str
    deviceCode: str
    scenarioId: str
    workerGeneration: int
    status: str
    lastSubmittedStep: int
    totalAccepted: int
    totalDuplicates: int
    lastError: Optional[str] = None


class WorkerStatusResponse(BaseModel):
    workerInstanceId: str
    activeRuns: int
    runs: Dict[str, RunStatusSnapshot]


class WorkerHealthResponse(BaseModel):
    status: str
    workerInstanceId: str
    activeRuns: int
    databaseAccess: bool = False


class InternalErrorResponse(BaseModel):
    code: str
    message: str
    correlationId: str
