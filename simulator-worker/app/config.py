"""Configuration loader for the standalone SomniSense simulator worker."""

from __future__ import annotations

import os
import uuid
from dataclasses import dataclass

MIN_KEY_LENGTH = 32


@dataclass(frozen=True)
class Settings:
    internal_key: str
    ingestion_api_key: str
    backend_base_url: str
    worker_instance_id: str
    host: str = "127.0.0.1"
    port: int = 8095
    batch_size: int = 5
    flush_interval_seconds: float = 1.0
    http_timeout_seconds: float = 5.0
    max_ingest_retries: int = 5
    max_pending_steps: int = 120


def load_settings() -> Settings:
    batch_size = max(1, min(600, int(os.environ.get("SIM_BATCH_SIZE", "5"))))
    flush_interval = max(0.1, float(os.environ.get("SIM_FLUSH_INTERVAL_SECONDS", "1.0")))
    http_timeout = max(0.2, float(os.environ.get("SIM_HTTP_TIMEOUT_SECONDS", "5.0")))
    max_retries = max(0, int(os.environ.get("SIM_MAX_INGEST_RETRIES", "5")))
    max_pending = max(batch_size, int(os.environ.get("SIM_MAX_PENDING_STEPS", "120")))
    worker_id = os.environ.get("SIM_WORKER_INSTANCE_ID", "").strip() or f"sim-worker-{uuid.uuid4().hex[:8]}"
    backend_url = os.environ.get("BACKEND_BASE_URL", "http://127.0.0.1:8080").strip().rstrip("/")

    return Settings(
        internal_key=os.environ.get("SIM_WORKER_INTERNAL_KEY", "").strip(),
        ingestion_api_key=os.environ.get("INGESTION_API_KEY", "").strip(),
        backend_base_url=backend_url,
        worker_instance_id=worker_id,
        host=os.environ.get("SIM_WORKER_HOST", "127.0.0.1"),
        port=int(os.environ.get("SIM_WORKER_PORT", "8095")),
        batch_size=batch_size,
        flush_interval_seconds=flush_interval,
        http_timeout_seconds=http_timeout,
        max_ingest_retries=max_retries,
        max_pending_steps=max_pending,
    )
