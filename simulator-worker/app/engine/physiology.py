"""
SomniSense S2 Cardiopulmonary & Hemodynamic Autonomic Models.
Ported faithfully from the Stage 1/Phase 9 simulation engine.
"""

from __future__ import annotations

import collections
import math
from typing import Optional
from numpy.random import Generator

from app.engine.scenarios import EventEtiology, ScenarioDescriptor


class CardiopulmonaryModel:
    """Simulates pulmonary oxygenation kinetics and lung-to-finger transit delay via a 251-entry FIFO."""

    MAX_TRANSIT_DELAY_SECONDS: float = 25.0
    DEFAULT_PHYSICS_STEP_SECONDS: float = 0.1

    def __init__(
        self,
        scenario: ScenarioDescriptor,
        physics_step_seconds: float = DEFAULT_PHYSICS_STEP_SECONDS,
        prng: Optional[Generator] = None,
    ) -> None:
        self._scenario = scenario
        self._physics_step = float(physics_step_seconds)
        self._prng = prng
        self._buffer_capacity: int = math.ceil(self.MAX_TRANSIT_DELAY_SECONDS / self._physics_step) + 1
        self._buffer: collections.deque[float] = collections.deque(maxlen=self._buffer_capacity)
        self._sim_time: float = 0.0
        self._lung_spo2: float = float(scenario.base_spo2)
        self._airflow: float = 1.0
        self._step_counter: int = 0
        self._steps_delay: int = int(round(scenario.circulation_transit_delay_seconds / self._physics_step))
        self._initialize_buffer()

    def _initialize_buffer(self) -> None:
        self._buffer.clear()
        base = float(self._scenario.base_spo2)
        for _ in range(self._buffer_capacity):
            self._buffer.append(base)

    def reset(self) -> None:
        self._sim_time = 0.0
        self._step_counter = 0
        self._lung_spo2 = float(self._scenario.base_spo2)
        self._airflow = 1.0
        self._initialize_buffer()

    def step(self) -> None:
        dt = self._physics_step
        t = self._sim_time
        sc = self._scenario
        cycle_period = sc.cycle_period_seconds
        event_dur = sc.event_duration_seconds
        t_mod = t % cycle_period if cycle_period > 0.0 else 0.0
        is_event_active = (event_dur > 0.0) and (t_mod < event_dur)

        if sc.etiology == EventEtiology.NORMAL or event_dur <= 0.0:
            airflow = 1.0
        elif is_event_active:
            airflow = max(0.0, 1.0 - sc.airflow_reduction_fraction)
        elif sc.etiology == EventEtiology.CSA:
            rec_duration = cycle_period - event_dur
            t_rec = t_mod - event_dur
            phase = math.pi * (t_rec / rec_duration) if rec_duration > 0.0 else 0.0
            airflow = 1.0 + 0.6 * math.sin(phase)
        else:
            airflow = 1.0

        self._airflow = float(airflow)
        base_spo2 = sc.base_spo2
        nadir_target = max(40.0, base_spo2 - sc.max_desaturation_target_depth)

        if is_event_active and airflow < 1.0:
            new_lung_spo2 = max(nadir_target, self._lung_spo2 - sc.desaturation_rate * dt)
        else:
            new_lung_spo2 = min(base_spo2, self._lung_spo2 + sc.resaturation_rate * airflow * dt)

        self._lung_spo2 = min(100.0, max(40.0, new_lung_spo2))
        self._buffer.append(self._lung_spo2)
        self._step_counter += 1
        self._sim_time = self._step_counter * dt

    def advance_to(self, target_time_seconds: float) -> None:
        if target_time_seconds < self._sim_time:
            self.reset()
        while self._sim_time + (0.5 * self._physics_step) < target_time_seconds:
            self.step()

    def get_finger_spo2(self) -> float:
        buf_len = len(self._buffer)
        if buf_len == 0:
            return float(self._scenario.base_spo2)
        idx = max(0, min(buf_len - 1, buf_len - 1 - self._steps_delay))
        return min(100.0, max(40.0, float(self._buffer[idx])))


class HemodynamicModel:
    """Simulates autonomic heart rate regulation: baseline, RSA, vagal bradycardia, and arousal surge."""

    RESPIRATORY_FREQUENCY_HZ: float = 0.25

    def __init__(self, scenario: ScenarioDescriptor, prng: Optional[Generator] = None) -> None:
        self._scenario = scenario
        self._prng = prng

    def compute_hr(self, time_seconds: float) -> float:
        sc = self._scenario
        t = float(time_seconds)
        cycle_period = sc.cycle_period_seconds
        event_dur = sc.event_duration_seconds
        t_mod = t % cycle_period if cycle_period > 0.0 else 0.0
        is_event_active = (event_dur > 0.0) and (t_mod < event_dur)

        base_rsa_amp = float(sc.rsa_amplitude_bpm)
        if is_event_active and sc.airflow_reduction_fraction > 0.0:
            rsa_amp = base_rsa_amp * (1.0 - 0.5 * sc.airflow_reduction_fraction)
        else:
            rsa_amp = base_rsa_amp
        hr_rsa = rsa_amp * math.sin(2.0 * math.pi * self.RESPIRATORY_FREQUENCY_HZ * t)

        if is_event_active and event_dur > 0.0:
            progress = t_mod / event_dur
            hr_vagal = -float(sc.bradycardia_depth_bpm) * float(sc.airflow_reduction_fraction) * progress
        else:
            hr_vagal = 0.0

        if (event_dur > 0.0) and (t_mod >= event_dur):
            t_post = t_mod - event_dur
            tau = max(0.1, float(sc.arousal_decay_tau_seconds))
            surge_amp = float(sc.tachycardia_surge_bpm) * float(sc.airflow_reduction_fraction)
            hr_surge = surge_amp * math.exp(-t_post / tau)
        else:
            hr_surge = 0.0

        hrv_wander = float(self._prng.normal(0.0, 0.15)) if self._prng is not None else 0.0
        total_hr = float(sc.base_hr) + hr_rsa + hr_vagal + hr_surge + hrv_wander
        return min(250.0, max(20.0, total_hr))
