"""
SomniSense S1-S3 Core Simulation Engine for the standalone worker.
Supports seeded deterministic sample generation, 1-based step progression, and fast-forward
warmup so worker resume/recovery matches continuous runs bit-for-bit.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Optional

from app.engine.degradation import QualityDegradationConfig, QualityDegradationEngine, resolve_quality_preset
from app.engine.physiology import CardiopulmonaryModel, HemodynamicModel
from app.engine.rng import SeededRNGManager
from app.engine.scenarios import ScenarioDescriptor, ScenarioLibrary


@dataclass(frozen=True)
class SimulatedReading:
    step_index: int        # 0-based internal sample index
    step_number: int       # 1-based backend checkpoint step (= step_index + 1)
    elapsed_seconds: float
    spo2: Optional[float]
    heart_rate: Optional[float]


class SimulationEngine:
    """Deterministic S1-S3 sample generator with O(1) memory footprint."""

    def __init__(
        self,
        scenario_id: Optional[str] = "normal_sleep",
        seed: Optional[int] = 42,
        cadence_hz: float = 1.0,
        playback_speed: float = 1.0,
        quality_preset: Optional[str] = None,
        quality_config: Optional[QualityDegradationConfig] = None,
        base_spo2: Optional[float] = None,
        base_hr: Optional[float] = None,
    ) -> None:
        if not math.isfinite(cadence_hz) or cadence_hz <= 0.0:
            raise ValueError(f"cadence_hz must be finite and > 0.0, got {cadence_hz}")
        if not math.isfinite(playback_speed) or playback_speed <= 0.0:
            raise ValueError(f"playback_speed must be finite and > 0.0, got {playback_speed}")

        self.cadence_hz = float(cadence_hz)
        self.delta_t = 1.0 / self.cadence_hz
        self.playback_speed = float(playback_speed)

        self._scenario: Optional[ScenarioDescriptor] = ScenarioLibrary.get(scenario_id) if scenario_id else None
        self.base_spo2 = float(base_spo2) if base_spo2 is not None else (
            self._scenario.base_spo2 if self._scenario else 97.0
        )
        self.base_hr = float(base_hr) if base_hr is not None else (
            self._scenario.base_hr if self._scenario else 68.0
        )

        self._rng = SeededRNGManager(seed)
        self.resolved_seed = self._rng.resolved_seed
        self._cardiopulmonary: Optional[CardiopulmonaryModel] = None
        self._hemodynamic: Optional[HemodynamicModel] = None
        if self._scenario is not None:
            self._cardiopulmonary = CardiopulmonaryModel(
                scenario=self._scenario,
                physics_step_seconds=0.1,
                prng=self._rng.physiology,
            )
            self._hemodynamic = HemodynamicModel(
                scenario=self._scenario,
                prng=self._rng.physiology,
            )

        resolved_q = quality_config or resolve_quality_preset(quality_preset)
        self._degradation = QualityDegradationEngine(resolved_q, prng=self._rng.noise) if resolved_q else None

    def fast_forward_before_step(self, start_step_one_based: int) -> None:
        """
        Replays steps 1 .. start_step_one_based - 1 in memory so physiology buffers and all three
        PRNG streams match an uninterrupted run identically on resume/restart.
        """
        target_idx = max(0, int(start_step_one_based) - 1)
        for idx in range(target_idx):
            self.generate_zero_based(idx)

    def generate_step(self, step_one_based: int) -> SimulatedReading:
        if step_one_based < 1:
            raise ValueError(f"1-based step must be >= 1, got {step_one_based}")
        return self.generate_zero_based(step_one_based - 1)

    def generate_zero_based(self, step_idx: int) -> SimulatedReading:
        elapsed_sec = step_idx * self.delta_t
        if self._cardiopulmonary is not None and self._hemodynamic is not None:
            self._cardiopulmonary.advance_to(elapsed_sec)
            phys_spo2 = self._cardiopulmonary.get_finger_spo2()
            phys_hr = self._hemodynamic.compute_hr(elapsed_sec)

            drift_spo2 = 0.05 * math.sin(2.0 * math.pi * 0.005 * elapsed_sec)
            phys_spo2_var = float(self._rng.physiology.normal(0.0, 0.03))
            noise_spo2 = float(self._rng.noise.normal(0.0, 0.08))
            noise_hr = float(self._rng.noise.normal(0.0, 0.15))

            raw_spo2 = phys_spo2 + drift_spo2 + phys_spo2_var + noise_spo2
            raw_hr = phys_hr + noise_hr
        else:
            drift_spo2 = 0.2 * math.sin(2.0 * math.pi * 0.01 * elapsed_sec)
            rsa_hr = 1.5 * math.sin(2.0 * math.pi * 0.25 * elapsed_sec)
            phys_spo2_var = float(self._rng.physiology.normal(0.0, 0.05))
            phys_hr_var = float(self._rng.physiology.normal(0.0, 0.2))
            noise_spo2 = float(self._rng.noise.normal(0.0, 0.08))
            noise_hr = float(self._rng.noise.normal(0.0, 0.15))

            raw_spo2 = self.base_spo2 + drift_spo2 + phys_spo2_var + noise_spo2
            raw_hr = self.base_hr + rsa_hr + phys_hr_var + noise_hr

        spo2: Optional[float] = round(min(100.0, max(40.0, raw_spo2)), 2)
        hr: Optional[float] = round(min(250.0, max(20.0, raw_hr)), 2)

        if self._degradation is not None:
            spo2, hr = self._degradation.apply(elapsed_sec, spo2, hr)

        return SimulatedReading(
            step_index=step_idx,
            step_number=step_idx + 1,
            elapsed_seconds=elapsed_sec,
            spo2=spo2,
            heart_rate=hr,
        )
