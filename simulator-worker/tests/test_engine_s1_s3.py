"""Tests for the ported S1-S3 simulation engine: determinism, waveforms, presets, and boundaries."""

from __future__ import annotations

import pathlib

from app.engine.core import SimulationEngine
from app.engine.degradation import QualityDegradationConfig, QualityDegradationEngine
from app.engine.scenarios import ScenarioLibrary


def test_seeded_determinism_and_step_resume_fast_forward():
    e1 = SimulationEngine(scenario_id="normal_sleep", seed=777, cadence_hz=1.0, playback_speed=1.0)
    e2 = SimulationEngine(scenario_id="normal_sleep", seed=777, cadence_hz=1.0, playback_speed=5.0)

    seq1 = [e1.generate_step(step) for step in range(1, 25)]
    # Playback speed must never alter physiological sample values or observation elapsed timestamps
    seq2 = [e2.generate_step(step) for step in range(1, 25)]
    assert [(s.step_number, s.elapsed_seconds, s.spo2, s.heart_rate) for s in seq1] == [
        (s.step_number, s.elapsed_seconds, s.spo2, s.heart_rate) for s in seq2
    ]

    # Resuming at step 15 after fast-forward must be bitwise identical to continuous steps 15..24
    e3 = SimulationEngine(scenario_id="normal_sleep", seed=777, cadence_hz=1.0, playback_speed=1.0)
    e3.fast_forward_before_step(15)
    resumed = [e3.generate_step(step) for step in range(15, 25)]
    assert resumed == seq1[14:24]


def test_s2_scenarios_produce_distinct_physiological_dynamics():
    normal = SimulationEngine(scenario_id="normal_sleep", seed=100)
    osa = SimulationEngine(scenario_id="severe_osa", seed=100)
    csa = SimulationEngine(scenario_id="cheyne_stokes_csa", seed=100)
    hyp = SimulationEngine(scenario_id="moderate_hypopnea", seed=100)

    normal_samples = [normal.generate_step(s) for s in range(1, 121)]
    osa_samples = [osa.generate_step(s) for s in range(1, 121)]
    csa_samples = [csa.generate_step(s) for s in range(1, 121)]
    hyp_samples = [hyp.generate_step(s) for s in range(1, 121)]

    # Normal stays stable near baseline (~97.5%); OSA shows deeper desaturation nadir and wider HR surge range
    assert min(s.spo2 for s in normal_samples) > 95.0
    assert min(s.spo2 for s in osa_samples) < 90.0
    assert max(s.heart_rate for s in osa_samples) > max(s.heart_rate for s in normal_samples)
    # Hypopnea shows milder drop than complete OSA obstruction; CSA oscillates within physiological bounds
    assert min(s.spo2 for s in hyp_samples) > min(s.spo2 for s in osa_samples)
    assert all(40.0 <= s.spo2 <= 100.0 and 20.0 <= s.heart_rate <= 250.0 for s in csa_samples)
    assert len(ScenarioLibrary.list_scenarios()) >= 4


def test_s3_quality_presets_and_transient_dropouts():
    # Preset TRANSIENT_DROPOUT injects null spo2/hr strictly inside configured intervals while staying bounded elsewhere
    e_drop = SimulationEngine(scenario_id="normal_sleep", seed=42, quality_preset="TRANSIENT_DROPOUT")
    samples = [e_drop.generate_step(step) for step in range(1, 35)]
    # step 21 -> elapsed 20.0s falls inside [20.0, 28.0) -> None/None
    assert samples[20].spo2 is None and samples[20].heart_rate is None
    assert samples[27].spo2 is None and samples[27].heart_rate is None
    assert samples[19].spo2 is not None and samples[28].spo2 is not None

    # Noisy preset keeps values within valid clinical bounds
    e_noisy = SimulationEngine(scenario_id="severe_osa", seed=42, quality_preset="NOISY")
    noisy_samples = [e_noisy.generate_step(step) for step in range(1, 61)]
    assert all(40.0 <= s.spo2 <= 100.0 and 20.0 <= s.heart_rate <= 250.0 for s in noisy_samples)

    # Custom motion artifact shift
    q = QualityDegradationEngine(QualityDegradationConfig(
        motion_artifacts_enabled=True,
        motion_artifact_intervals=[(5.0, 7.0, -5.0, 18.0)],
    ))
    sp, hr = q.apply(5.0, 96.0, 68.0)
    assert sp == 91.0 and hr == 86.0


def test_simulator_worker_contains_no_database_or_ai_model_dependencies():
    root = pathlib.Path(__file__).resolve().parents[1]
    forbidden = ("psycopg", "asyncpg", "sqlalchemy", "pg8000", "joblib", "scikit-learn", "sklearn", "evaluate-window")
    for path in list(root.glob("app/**/*.py")) + [root / "requirements.txt"]:
        content = path.read_text(encoding="utf-8").lower()
        for token in forbidden:
            assert token not in content, f"Forbidden dependency/reference {token!r} found in {path}"
