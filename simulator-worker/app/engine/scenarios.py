"""
SomniSense S2 Physiological Scenario Definitions and Presets Library.
Never attaches or persists ground-truth event labels into SomniSense PostgreSQL tables.
"""

from __future__ import annotations

import math
from enum import Enum
from typing import Dict, List
from pydantic import BaseModel, Field, field_validator


class EventEtiology(str, Enum):
    NORMAL = "NORMAL"
    OSA = "OSA"
    CSA = "CSA"
    HYPOPNEA = "HYPOPNEA"


class ScenarioDescriptor(BaseModel):
    scenario_id: str = Field(...)
    name: str = Field(...)
    description: str = Field(...)
    etiology: EventEtiology = Field(...)

    base_spo2: float = Field(default=97.0)
    base_hr: float = Field(default=68.0)

    event_duration_seconds: float = Field(default=25.0)
    cycle_period_seconds: float = Field(default=120.0)
    circulation_transit_delay_seconds: float = Field(default=18.0)

    airflow_reduction_fraction: float = Field(default=1.0)
    desaturation_rate: float = Field(default=0.35)
    resaturation_rate: float = Field(default=0.45)
    max_desaturation_target_depth: float = Field(default=10.0)

    rsa_amplitude_bpm: float = Field(default=2.0)
    bradycardia_depth_bpm: float = Field(default=8.0)
    tachycardia_surge_bpm: float = Field(default=14.0)
    arousal_decay_tau_seconds: float = Field(default=8.0)

    @field_validator("scenario_id", "name", "description")
    @classmethod
    def validate_non_empty_strings(cls, v: str) -> str:
        if not isinstance(v, str) or not v.strip():
            raise ValueError("String fields must be non-empty")
        return v.strip()

    @field_validator("base_spo2")
    @classmethod
    def validate_base_spo2(cls, v: float) -> float:
        if not math.isfinite(v) or not (40.0 <= v <= 100.0):
            raise ValueError(f"base_spo2 must be in [40.0, 100.0], got {v}")
        return float(v)

    @field_validator("base_hr")
    @classmethod
    def validate_base_hr(cls, v: float) -> float:
        if not math.isfinite(v) or not (20.0 <= v <= 250.0):
            raise ValueError(f"base_hr must be in [20.0, 250.0], got {v}")
        return float(v)

    @field_validator("circulation_transit_delay_seconds")
    @classmethod
    def validate_transit_delay(cls, v: float) -> float:
        if not math.isfinite(v) or not (15.0 <= v <= 25.0):
            raise ValueError(f"circulation_transit_delay_seconds must be in [15.0, 25.0], got {v}")
        return float(v)

    @field_validator("airflow_reduction_fraction")
    @classmethod
    def validate_reduction(cls, v: float) -> float:
        if not math.isfinite(v) or not (0.0 <= v <= 1.0):
            raise ValueError(f"airflow_reduction_fraction must be in [0.0, 1.0], got {v}")
        return float(v)

    @field_validator("event_duration_seconds")
    @classmethod
    def validate_event_duration(cls, v: float) -> float:
        if not math.isfinite(v) or v < 0.0:
            raise ValueError(f"event_duration_seconds must be >= 0.0, got {v}")
        return float(v)

    @field_validator("cycle_period_seconds")
    @classmethod
    def validate_cycle_period(cls, v: float) -> float:
        if not math.isfinite(v) or v <= 0.0:
            raise ValueError(f"cycle_period_seconds must be > 0.0, got {v}")
        return float(v)


def get_normal_sleep_scenario() -> ScenarioDescriptor:
    return ScenarioDescriptor(
        scenario_id="normal_sleep",
        name="Normal Sleep Breathing",
        description="Stable resting sleep breathing with baseline RSA and zero desaturations.",
        etiology=EventEtiology.NORMAL,
        base_spo2=97.5,
        base_hr=64.0,
        event_duration_seconds=0.0,
        cycle_period_seconds=60.0,
        circulation_transit_delay_seconds=18.0,
        airflow_reduction_fraction=0.0,
        desaturation_rate=0.0,
        resaturation_rate=0.5,
        max_desaturation_target_depth=0.0,
        rsa_amplitude_bpm=2.0,
        bradycardia_depth_bpm=0.0,
        tachycardia_surge_bpm=0.0,
        arousal_decay_tau_seconds=8.0,
    )


def get_severe_osa_scenario() -> ScenarioDescriptor:
    return ScenarioDescriptor(
        scenario_id="severe_osa",
        name="Severe Obstructive Sleep Apnea",
        description="Recurrent 30s upper airway collapses every 100s with deep desaturations and arousal surges.",
        etiology=EventEtiology.OSA,
        base_spo2=96.5,
        base_hr=68.0,
        event_duration_seconds=30.0,
        cycle_period_seconds=100.0,
        circulation_transit_delay_seconds=18.0,
        airflow_reduction_fraction=1.0,
        desaturation_rate=0.40,
        resaturation_rate=0.55,
        max_desaturation_target_depth=12.0,
        rsa_amplitude_bpm=2.2,
        bradycardia_depth_bpm=9.0,
        tachycardia_surge_bpm=16.0,
        arousal_decay_tau_seconds=8.0,
    )


def get_cheyne_stokes_csa_scenario() -> ScenarioDescriptor:
    return ScenarioDescriptor(
        scenario_id="cheyne_stokes_csa",
        name="Cheyne-Stokes Central Sleep Apnea",
        description="Periodic central cessation of breathing effort followed by crescendo-decrescendo cycles.",
        etiology=EventEtiology.CSA,
        base_spo2=95.0,
        base_hr=72.0,
        event_duration_seconds=20.0,
        cycle_period_seconds=60.0,
        circulation_transit_delay_seconds=24.0,
        airflow_reduction_fraction=1.0,
        desaturation_rate=0.30,
        resaturation_rate=0.35,
        max_desaturation_target_depth=8.0,
        rsa_amplitude_bpm=1.8,
        bradycardia_depth_bpm=5.0,
        tachycardia_surge_bpm=7.0,
        arousal_decay_tau_seconds=10.0,
    )


def get_moderate_hypopnea_scenario() -> ScenarioDescriptor:
    return ScenarioDescriptor(
        scenario_id="moderate_hypopnea",
        name="Moderate Hypopnea",
        description="Recurrent 25s partial upper airway collapsibility (50% reduction) with moderate desaturations.",
        etiology=EventEtiology.HYPOPNEA,
        base_spo2=96.0,
        base_hr=66.0,
        event_duration_seconds=25.0,
        cycle_period_seconds=120.0,
        circulation_transit_delay_seconds=16.0,
        airflow_reduction_fraction=0.5,
        desaturation_rate=0.25,
        resaturation_rate=0.45,
        max_desaturation_target_depth=5.5,
        rsa_amplitude_bpm=2.0,
        bradycardia_depth_bpm=4.0,
        tachycardia_surge_bpm=9.0,
        arousal_decay_tau_seconds=7.0,
    )


class ScenarioLibrary:
    """Registry for supported physiological simulation scenarios and canonical aliases."""

    _REGISTRY: Dict[str, ScenarioDescriptor] = {
        "normal_sleep": get_normal_sleep_scenario(),
        "severe_osa": get_severe_osa_scenario(),
        "cheyne_stokes_csa": get_cheyne_stokes_csa_scenario(),
        "moderate_hypopnea": get_moderate_hypopnea_scenario(),
    }

    _ALIASES: Dict[str, str] = {
        "s1_normal": "normal_sleep",
        "s2_osa": "severe_osa",
        "s2_csa": "cheyne_stokes_csa",
        "s2_hypopnea": "moderate_hypopnea",
        "mild_osa": "moderate_hypopnea",
    }

    @classmethod
    def get(cls, scenario_id: str) -> ScenarioDescriptor:
        raw = (scenario_id or "").strip()
        canonical = cls._ALIASES.get(raw.lower(), raw)
        if canonical not in cls._REGISTRY:
            available = ", ".join(repr(k) for k in cls._REGISTRY.keys())
            raise KeyError(f"Unknown scenario_id '{scenario_id}'. Available scenarios: {available}")
        return cls._REGISTRY[canonical].model_copy(deep=True)

    @classmethod
    def list_scenarios(cls) -> List[str]:
        return list(cls._REGISTRY.keys())
