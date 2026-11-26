"""
SomniSense S3 Signal Quality Degradation Engine & Named Presets.
Strictly consumes Stream C (noise) so physiological PRNG state stays isolated.
"""

from __future__ import annotations

import math
from typing import Dict, List, Optional, Tuple
from numpy.random import Generator, PCG64
from pydantic import BaseModel, Field, field_validator


class PinkNoiseGenerator:
    """Bounded 1/f Pink Noise Generator (Paul Kellet filter)."""

    def __init__(self, prng: Generator, scale: float = 1.0) -> None:
        self._prng = prng
        self._scale = float(scale)
        self.reset()

    def reset(self) -> None:
        self._b0 = self._b1 = self._b2 = self._b3 = self._b4 = self._b5 = self._b6 = 0.0

    def next_sample(self) -> float:
        white = float(self._prng.normal(0.0, 1.0))
        self._b0 = 0.99886 * self._b0 + white * 0.0555179
        self._b1 = 0.99332 * self._b1 + white * 0.0750759
        self._b2 = 0.96900 * self._b2 + white * 0.1538520
        self._b3 = 0.86650 * self._b3 + white * 0.3104856
        self._b4 = 0.55000 * self._b4 + white * 0.5329522
        self._b5 = -0.7616 * self._b5 - white * 0.0168980
        pink_val = (
            self._b0 + self._b1 + self._b2 + self._b3 + self._b4 + self._b5 + self._b6 + white * 0.5362
        ) * 0.11
        self._b6 = white * 0.115926
        return max(-4.0, min(4.0, pink_val)) * self._scale


class QualityDegradationConfig(BaseModel):
    gaussian_noise_enabled: bool = Field(default=False)
    spo2_noise_std: float = Field(default=0.08)
    hr_noise_std: float = Field(default=0.15)
    pink_noise_enabled: bool = Field(default=False)
    pink_noise_spo2_scale: float = Field(default=0.05)
    pink_noise_hr_scale: float = Field(default=0.12)
    motion_artifacts_enabled: bool = Field(default=False)
    motion_artifact_intervals: List[Tuple[float, float, float, float]] = Field(default_factory=list)
    dropouts_enabled: bool = Field(default=True)
    transient_dropout_intervals: List[Tuple[float, float]] = Field(default_factory=list)
    prolonged_disconnection_intervals: List[Tuple[float, float]] = Field(default_factory=list)
    stochastic_dropout_probability: float = Field(default=0.0)

    @field_validator("spo2_noise_std", "hr_noise_std", "pink_noise_spo2_scale", "pink_noise_hr_scale", "stochastic_dropout_probability")
    @classmethod
    def validate_non_negative_finite(cls, v: float) -> float:
        if not math.isfinite(v) or v < 0.0:
            raise ValueError(f"Value must be finite and >= 0.0, got {v}")
        return float(v)


def resolve_quality_preset(preset_name: Optional[str]) -> Optional[QualityDegradationConfig]:
    """Maps optional Spring Boot SimulationRun.qualityPreset names onto S3 configurations."""
    if not preset_name or not preset_name.strip():
        return None
    key = preset_name.strip().upper()
    presets: Dict[str, Optional[QualityDegradationConfig]] = {
        "CLEAN": None,
        "DEFAULT": None,
        "NOISY": QualityDegradationConfig(
            gaussian_noise_enabled=True,
            spo2_noise_std=0.25,
            hr_noise_std=0.50,
            pink_noise_enabled=True,
            pink_noise_spo2_scale=0.15,
            pink_noise_hr_scale=0.30,
            dropouts_enabled=False,
        ),
        "TRANSIENT_DROPOUT": QualityDegradationConfig(
            gaussian_noise_enabled=True,
            spo2_noise_std=0.12,
            hr_noise_std=0.25,
            dropouts_enabled=True,
            transient_dropout_intervals=[(20.0, 28.0), (85.0, 93.0)],
        ),
        "DEGRADED": QualityDegradationConfig(
            gaussian_noise_enabled=True,
            spo2_noise_std=0.30,
            hr_noise_std=0.75,
            pink_noise_enabled=True,
            dropouts_enabled=True,
            stochastic_dropout_probability=0.35,
            transient_dropout_intervals=[(15.0, 27.0)],
        ),
        "PROLONGED_DISCONNECT": QualityDegradationConfig(
            dropouts_enabled=True,
            prolonged_disconnection_intervals=[(10.0, 45.0)],
        ),
    }
    if key not in presets:
        available = ", ".join(sorted(presets.keys()))
        raise ValueError(f"Unknown quality_preset '{preset_name}'. Available presets: {available}")
    cfg = presets[key]
    return cfg.model_copy(deep=True) if cfg is not None else None


class QualityDegradationEngine:
    """Applies Stream C measurement noise, artifacts, and dropouts safely within clinical bounds."""

    def __init__(self, config: QualityDegradationConfig, prng: Optional[Generator] = None) -> None:
        self._config = config
        self._prng = prng if prng is not None else Generator(PCG64(42))
        self._pink_spo2 = PinkNoiseGenerator(self._prng, scale=config.pink_noise_spo2_scale)
        self._pink_hr = PinkNoiseGenerator(self._prng, scale=config.pink_noise_hr_scale)

    def apply(self, elapsed_sec: float, spo2: Optional[float], hr: Optional[float]) -> Tuple[Optional[float], Optional[float]]:
        t = float(elapsed_sec)
        if self._config.dropouts_enabled:
            for s, e in self._config.prolonged_disconnection_intervals:
                if s <= t < e:
                    return None, None
            for s, e in self._config.transient_dropout_intervals:
                if s <= t < e:
                    return None, None
            if self._config.stochastic_dropout_probability > 0.0:
                if float(self._prng.random()) < self._config.stochastic_dropout_probability:
                    return None, None

        if spo2 is None or hr is None:
            return spo2, hr

        out_spo2 = float(spo2)
        out_hr = float(hr)

        if self._config.gaussian_noise_enabled:
            out_spo2 += float(self._prng.normal(0.0, self._config.spo2_noise_std))
            out_hr += float(self._prng.normal(0.0, self._config.hr_noise_std))

        if self._config.pink_noise_enabled:
            out_spo2 += self._pink_spo2.next_sample()
            out_hr += self._pink_hr.next_sample()

        if self._config.motion_artifacts_enabled or self._config.motion_artifact_intervals:
            for s, e, sp_sh, hr_sh in self._config.motion_artifact_intervals:
                if s <= t < e:
                    out_spo2 += sp_sh
                    out_hr += hr_sh

        out_spo2 = round(min(100.0, max(40.0, out_spo2)), 2)
        out_hr = round(min(250.0, max(20.0, out_hr)), 2)
        return out_spo2, out_hr
