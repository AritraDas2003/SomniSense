"""Configuration for the internal SomniSense Model C FastAPI sidecar."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


EXPECTED_MODEL_SHA256 = "faef00b08c6406bb0277c6ca05f15239acfc8365561d9c9a07eba3554939a6ca"
EXPECTED_SCHEMA_SHA256 = "c4d1b2e26a15569cef6328d8e4d06f5dc5677f0d8b74f709f30c104715d472f7"
MODEL_VERSION_CONTRACT = "phase7_model_c:1.0.0"
MIN_API_KEY_LENGTH = 32


@dataclass(frozen=True)
class Settings:
    api_key: str
    artifact_dir: Path
    host: str
    port: int
    expected_model_sha256: str = EXPECTED_MODEL_SHA256
    expected_schema_sha256: str = EXPECTED_SCHEMA_SHA256


def load_settings() -> Settings:
    default_artifact_dir = Path(__file__).resolve().parents[1] / "artifacts" / "model_c"
    artifact_dir = Path(os.environ.get("MODEL_C_ARTIFACT_DIR", str(default_artifact_dir))).resolve()
    return Settings(
        api_key=os.environ.get("AI_SERVICE_API_KEY", "").strip(),
        artifact_dir=artifact_dir,
        host=os.environ.get("AI_SERVICE_HOST", "127.0.0.1"),
        port=int(os.environ.get("AI_SERVICE_PORT", "8090")),
    )
