"""
Self-contained Phase 5B / Phase 7 / Phase 8 signal sanitization, 1 Hz causal grid builder,
rolling 300s baseline, 5-level quality classifier, and exact 16-feature extractor.
"""

from __future__ import annotations

import collections
import math
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, Optional, Tuple
import numpy as np

APPROVED_16_PREDICTORS = [
    "spo2_mean",
    "spo2_min",
    "spo2_max",
    "spo2_std",
    "spo2_rolling_baseline",
    "spo2_max_desaturation_depth",
    "spo2_time_below_90",
    "spo2_time_below_95",
    "hr_mean",
    "hr_min",
    "hr_max",
    "hr_std",
    "hr_range",
    "hr_slope",
    "hr_sampled_rmssd",
    "hr_mean_absolute_diff",
]

SPO2_MIN_VALID = 50.0
SPO2_MAX_VALID = 100.0
HR_MIN_VALID = 30.0
HR_MAX_VALID = 220.0

CAUSAL_CONTEXT_SECONDS = 360
WINDOW_DURATION_SECONDS = 60
BASELINE_BUFFER_SECONDS = 300


def validate_spo2_reading(val: Any) -> Optional[float]:
    """Returns float in [50.0, 100.0] or None if missing/out-of-range/non-numeric."""
    if val is None or val == "" or val == "-" or isinstance(val, bool):
        return None
    try:
        f = float(val)
    except (ValueError, TypeError):
        return None
    if not math.isfinite(f):
        return None
    return f if SPO2_MIN_VALID <= f <= SPO2_MAX_VALID else None


def validate_hr_reading(val: Any) -> Optional[float]:
    """Returns float in [30.0, 220.0] or None if missing/out-of-range/non-numeric."""
    if val is None or val == "" or val == "-" or isinstance(val, bool):
        return None
    try:
        f = float(val)
    except (ValueError, TypeError):
        return None
    if not math.isfinite(f):
        return None
    return f if HR_MIN_VALID <= f <= HR_MAX_VALID else None


def _to_utc_seconds(dt: datetime) -> float:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).timestamp()


def build_1hz_causal_grid(
    context_start: datetime,
    window_end: datetime,
    readings: Iterable[Any],
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """
    Maps arbitrary-order readings onto a deterministic 360-second 1 Hz grid [context_start, window_end).

    - Sorts deterministically by (measured_at ASC, received_at ASC, id ASC, input_index ASC).
    - Maps each reading with context_start <= measured_at < window_end via floor((measured_at - context_start)).
    - Sanitizes spo2 [50, 100] and heart_rate [30, 220] to float or np.nan.
    - Duplicate readings in the same 1s bucket use deterministic last-write-wins.
    - Missing seconds remain np.nan (never 0 or interpolated).
    """
    ctx_ts = _to_utc_seconds(context_start)
    end_ts = _to_utc_seconds(window_end)
    if round(end_ts - ctx_ts, 6) != float(CAUSAL_CONTEXT_SECONDS):
        raise ValueError(f"Causal context must span exactly {CAUSAL_CONTEXT_SECONDS}s")

    spo2_arr = np.full(CAUSAL_CONTEXT_SECONDS, np.nan, dtype=np.float64)
    hr_arr = np.full(CAUSAL_CONTEXT_SECONDS, np.nan, dtype=np.float64)
    timestamps = ctx_ts + np.arange(CAUSAL_CONTEXT_SECONDS, dtype=np.float64)

    indexed_rows = []
    for idx, r in enumerate(readings):
        m_ts = _to_utc_seconds(r.measured_at)
        r_ts = _to_utc_seconds(r.received_at) if getattr(r, "received_at", None) is not None else m_ts
        r_id = str(getattr(r, "id", None) or "")
        indexed_rows.append((m_ts, r_ts, r_id, idx, r))

    indexed_rows.sort(key=lambda item: (item[0], item[1], item[2], item[3]))

    for m_ts, _, _, _, r in indexed_rows:
        offset = m_ts - ctx_ts
        if offset < 0.0 or offset >= float(CAUSAL_CONTEXT_SECONDS):
            continue
        bucket = int(math.floor(offset))
        if 0 <= bucket < CAUSAL_CONTEXT_SECONDS:
            s_val = validate_spo2_reading(r.spo2)
            h_val = validate_hr_reading(r.heart_rate)
            spo2_arr[bucket] = float(s_val) if s_val is not None else np.nan
            hr_arr[bucket] = float(h_val) if h_val is not None else np.nan

    return spo2_arr, hr_arr, timestamps


def compute_gap_statistics(is_missing: np.ndarray) -> Tuple[int, int]:
    """Computes (longest_gap_seconds, gap_count) from a boolean missingness mask."""
    if len(is_missing) == 0 or not np.any(is_missing):
        return 0, 0
    if np.all(is_missing):
        return len(is_missing), 1

    longest_gap = 0
    gap_count = 0
    current_gap = 0
    for val in is_missing:
        if val:
            current_gap += 1
            if current_gap == 1:
                gap_count += 1
            if current_gap > longest_gap:
                longest_gap = current_gap
        else:
            current_gap = 0
    return longest_gap, gap_count


def evaluate_channel_quality(values: np.ndarray, expected_samples: int = 60) -> Dict[str, Any]:
    """Evaluates signal quality for one channel across a 60s window (5-level precedence ladder)."""
    total_samples = len(values)
    is_nan = np.isnan(values)
    valid_samples = int(np.sum(~is_nan))
    valid_fraction = float(valid_samples / total_samples) if total_samples > 0 else 0.0
    longest_gap, gap_count = compute_gap_statistics(is_nan)

    if valid_samples == 0:
        state = "EMPTY"
        rejection_reason = "REJECT_EMPTY"
    elif longest_gap > 15:
        state = "GAP_DOMINATED"
        rejection_reason = f"REJECT_GAP_DOMINATED_{longest_gap}S"
    elif valid_fraction < 0.50:
        state = "INSUFFICIENT"
        pct = int(round(valid_fraction * 100))
        rejection_reason = f"REJECT_INSUFFICIENT_COVERAGE_{pct}PCT"
    elif valid_fraction < 0.80 or longest_gap > 5:
        state = "PARTIAL_VALID"
        if longest_gap > 5:
            rejection_reason = f"SENSITIVITY_ONLY_GAP_{longest_gap}S"
        else:
            pct = int(round(valid_fraction * 100))
            rejection_reason = f"SENSITIVITY_ONLY_COVERAGE_{pct}PCT"
    else:
        state = "VALID"
        rejection_reason = "NONE_ELIGIBLE"

    return {
        "total_samples": total_samples,
        "valid_samples": valid_samples,
        "valid_fraction": valid_fraction,
        "longest_gap_seconds": longest_gap,
        "gap_count": gap_count,
        "quality_state": state,
        "rejection_reason": rejection_reason,
        "eligible_primary": bool(state == "VALID"),
        "eligible_sensitivity": bool(state in ("VALID", "PARTIAL_VALID")),
    }


def evaluate_combined_channel_quality(
    spo2_metrics: Dict[str, Any],
    hr_metrics: Dict[str, Any],
) -> Dict[str, Any]:
    """Combines SpO2 and HR channel quality into VALID, DEGRADED, or REJECTED."""
    spo2_state = spo2_metrics["quality_state"]
    hr_state = hr_metrics["quality_state"]
    unusable_states = {"EMPTY", "GAP_DOMINATED", "INSUFFICIENT"}

    if spo2_state in unusable_states or hr_state in unusable_states:
        combined_state = "REJECTED"
        reasons = []
        if spo2_state in unusable_states:
            reasons.append(f"SPO2:{spo2_metrics['rejection_reason']}")
        if hr_state in unusable_states:
            reasons.append(f"HR:{hr_metrics['rejection_reason']}")
        combined_rejection = ";".join(reasons)
    elif spo2_state == "PARTIAL_VALID" or hr_state == "PARTIAL_VALID":
        combined_state = "DEGRADED"
        reasons = []
        if spo2_state == "PARTIAL_VALID":
            reasons.append(f"SPO2:{spo2_metrics['rejection_reason']}")
        if hr_state == "PARTIAL_VALID":
            reasons.append(f"HR:{hr_metrics['rejection_reason']}")
        combined_rejection = ";".join(reasons) if reasons else "SENSITIVITY_ONLY_DEGRADED_CHANNEL"
    elif spo2_state == "VALID" and hr_state == "VALID":
        combined_state = "VALID"
        combined_rejection = "NONE_ELIGIBLE"
    else:
        combined_state = "REJECTED"
        combined_rejection = f"REJECT_UNRECOGNIZED_CHANNEL_STATE(SPO2:{spo2_state},HR:{hr_state})"

    combined_primary = bool(spo2_metrics["eligible_primary"] and hr_metrics["eligible_primary"])
    combined_sensitivity = bool(spo2_metrics["eligible_sensitivity"] and hr_metrics["eligible_sensitivity"])

    if combined_state == "VALID" and not combined_primary:
        combined_state = "REJECTED"
        combined_rejection = "REJECT_PRIMARY_ELIGIBILITY_DISAGREEMENT"

    return {
        "combined_quality_state": combined_state,
        "rejection_reason_combined": combined_rejection,
        "combined_eligible_primary": combined_primary,
        "combined_eligible_sensitivity": combined_sensitivity,
    }


class StreamingRollingBaseline:
    """Online 300-second causal 90th-percentile SpO2 baseline with post-outage warmup."""

    def __init__(
        self,
        buffer_seconds: int = 300,
        percentile: float = 90.0,
        min_valid_samples: int = 240,
        outage_gap_threshold: int = 5,
        warmup_streak_required: int = 60,
    ):
        self.buffer_seconds = buffer_seconds
        self.percentile = percentile
        self.min_valid_samples = min_valid_samples
        self.outage_gap_threshold = outage_gap_threshold
        self.warmup_streak_required = warmup_streak_required
        self.buffer: collections.deque[float] = collections.deque(maxlen=buffer_seconds)
        self.consecutive_valid_streak: int = 0
        self.current_gap_len: int = 0
        self.in_warmup: bool = True

    def update(self, spo2_val: Optional[float]) -> float:
        is_valid = spo2_val is not None and not math.isnan(spo2_val)
        self.buffer.append(float(spo2_val) if is_valid else np.nan)

        if is_valid:
            self.current_gap_len = 0
            self.consecutive_valid_streak += 1
            if self.in_warmup and self.consecutive_valid_streak >= self.warmup_streak_required:
                self.in_warmup = False
        else:
            self.current_gap_len += 1
            self.consecutive_valid_streak = 0
            if self.current_gap_len > self.outage_gap_threshold:
                self.in_warmup = True

        if self.in_warmup or not is_valid:
            return np.nan

        valid_in_buf = [v for v in self.buffer if not math.isnan(v)]
        if len(valid_in_buf) < self.min_valid_samples:
            return np.nan

        return float(np.percentile(valid_in_buf, self.percentile))


def extract_16_features(
    spo2_window: np.ndarray,
    hr_window: np.ndarray,
    baseline_window: np.ndarray,
    timestamps_window: np.ndarray,
    eligible: bool = True,
) -> Dict[str, float]:
    """Extracts the exact 16 Model C features across a 60-second window."""
    if not eligible:
        return {feat: np.nan for feat in APPROVED_16_PREDICTORS}

    valid_spo2 = spo2_window[~np.isnan(spo2_window)]
    n_valid_spo2 = len(valid_spo2)

    if n_valid_spo2 == 0:
        spo2_mean = np.nan
        spo2_min = np.nan
        spo2_max = np.nan
        spo2_std = np.nan
        spo2_time_below_90 = np.nan
        spo2_time_below_95 = np.nan
        spo2_rolling_base = np.nan
        spo2_max_depth = np.nan
    else:
        spo2_mean = float(np.mean(valid_spo2))
        spo2_min = float(np.min(valid_spo2))
        spo2_max = float(np.max(valid_spo2))
        spo2_std = float(np.std(valid_spo2, ddof=1)) if n_valid_spo2 >= 2 else 0.0
        spo2_time_below_90 = float(np.sum(valid_spo2 < 90.0))
        spo2_time_below_95 = float(np.sum(valid_spo2 < 95.0))

        valid_base = baseline_window[~np.isnan(baseline_window)]
        spo2_rolling_base = float(np.median(valid_base)) if len(valid_base) > 0 else np.nan

        mask_both = (~np.isnan(spo2_window)) & (~np.isnan(baseline_window))
        if np.any(mask_both):
            drops = np.maximum(0.0, baseline_window[mask_both] - spo2_window[mask_both])
            spo2_max_depth = float(np.max(drops))
        else:
            spo2_max_depth = np.nan

    valid_hr_mask = ~np.isnan(hr_window)
    valid_hr = hr_window[valid_hr_mask]
    n_valid_hr = len(valid_hr)

    if n_valid_hr == 0:
        hr_mean = np.nan
        hr_min = np.nan
        hr_max = np.nan
        hr_std = np.nan
        hr_range = np.nan
        hr_slope = np.nan
        hr_sampled_rmssd = np.nan
        hr_mean_abs_diff = np.nan
    else:
        hr_mean = float(np.mean(valid_hr))
        hr_min = float(np.min(valid_hr))
        hr_max = float(np.max(valid_hr))
        hr_std = float(np.std(valid_hr, ddof=1)) if n_valid_hr >= 2 else 0.0
        hr_range = float(hr_max - hr_min)

        if n_valid_hr >= 2:
            rel_times = timestamps_window[valid_hr_mask] - timestamps_window[0]
            var_t = float(np.var(rel_times))
            if var_t > 0:
                cov_t_hr = float(np.cov(rel_times, valid_hr)[0, 1])
                hr_slope = float(cov_t_hr / var_t)
            else:
                hr_slope = 0.0
        else:
            hr_slope = np.nan

        consec_diffs = []
        n_window = len(hr_window)
        for i in range(n_window - 1):
            if not np.isnan(hr_window[i]) and not np.isnan(hr_window[i + 1]):
                dt = timestamps_window[i + 1] - timestamps_window[i]
                if abs(dt - 1.0) < 1e-3:
                    consec_diffs.append(hr_window[i + 1] - hr_window[i])

        if len(consec_diffs) >= 20:
            arr_diffs = np.array(consec_diffs, dtype=np.float64)
            hr_sampled_rmssd = float(np.sqrt(np.mean(np.square(arr_diffs))))
            hr_mean_abs_diff = float(np.mean(np.abs(arr_diffs)))
        else:
            hr_sampled_rmssd = np.nan
            hr_mean_abs_diff = np.nan

    return {
        "spo2_mean": spo2_mean,
        "spo2_min": spo2_min,
        "spo2_max": spo2_max,
        "spo2_std": spo2_std,
        "spo2_rolling_baseline": spo2_rolling_base,
        "spo2_max_desaturation_depth": spo2_max_depth,
        "spo2_time_below_90": spo2_time_below_90,
        "spo2_time_below_95": spo2_time_below_95,
        "hr_mean": hr_mean,
        "hr_min": hr_min,
        "hr_max": hr_max,
        "hr_std": hr_std,
        "hr_range": hr_range,
        "hr_slope": hr_slope,
        "hr_sampled_rmssd": hr_sampled_rmssd,
        "hr_mean_absolute_diff": hr_mean_abs_diff,
    }
