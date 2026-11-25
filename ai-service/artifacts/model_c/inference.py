"""
SomniSense Phase 7 — Model C Standalone Inference Module.
Authoritative Gate 7 Packaging Deliverable.

Provides a deterministic, zero-fitting inference interface for Phase 7 Model C
(16-feature Logistic Regression Pipeline with Median Imputation and Standard Scaling).

Temporal Availability Contract:
Predictions are available only at window termination: t_avail = w_end.
The model consumes 16 pre-extracted features for a completed 60-second observation window.
"""

import os
import math
from typing import Dict, Any, List, Union, Optional
import numpy as np
import pandas as pd
import joblib

MODEL_VERSION = "1.0.0"
MODEL_ID = "phase7_model_c"
DEFAULT_DECISION_THRESHOLD = 0.50

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
    "hr_mean_absolute_diff"
]

# Optional metadata keys allowed alongside feature inputs
ALLOWED_METADATA_KEYS = {
    "patient_id",
    "window_index",
    "window_start",
    "window_end",
    "availability_timestamp",
    "timestamp"
}


class ModelCInferenceError(Exception):
    """Base exception for Model C inference failures."""
    pass


class InputValidationError(ModelCInferenceError):
    """Raised when input observation violates schema, types, or validity rules."""
    pass


class ModelArtifactError(ModelCInferenceError):
    """Raised when model artifact cannot be found or is corrupted."""
    pass


class ModelCInference:
    """
    Production inference engine for SomniSense Phase 7 Model C.
    Loads the serialized Scikit-Learn pipeline and executes strictly deterministic,
    non-fitting classification and probability estimation on 16-feature observation windows.
    """

    def __init__(self, model_path: Optional[str] = None, decision_threshold: float = DEFAULT_DECISION_THRESHOLD):
        """
        Initialize the inference engine.

        Parameters
        ----------
        model_path : str, optional
            Path to the serialized `model_c_pipeline.joblib`. If None, attempts to
            load the artifact from the same directory as this module.
        decision_threshold : float, default=0.50
            Classification probability threshold for positive (apnea) classification.
        """
        if model_path is None:
            model_path = os.path.join(os.path.dirname(__file__), "model_c_pipeline.joblib")

        self.model_path = os.path.abspath(model_path)
        self.decision_threshold = float(decision_threshold)
        self.pipeline = self._load_pipeline(self.model_path)
        self.feature_names = APPROVED_16_PREDICTORS.copy()
        self.model_version = MODEL_VERSION
        self.model_id = MODEL_ID

    def _load_pipeline(self, path: str):
        """Load and validate the serialized Scikit-Learn pipeline."""
        if not os.path.exists(path):
            raise ModelArtifactError(f"Model artifact not found at: {path}")

        try:
            pipeline = joblib.load(path)
        except Exception as e:
            raise ModelArtifactError(f"Failed to deserialize model artifact at {path}: {str(e)}") from e

        # Validate pipeline structure
        expected_steps = ["imputer", "scaler", "classifier"]
        if not hasattr(pipeline, "named_steps"):
            raise ModelArtifactError("Loaded object is not a valid Scikit-Learn Pipeline with named_steps.")

        for step in expected_steps:
            if step not in pipeline.named_steps:
                raise ModelArtifactError(f"Pipeline is missing required step: '{step}'. Found steps: {list(pipeline.named_steps.keys())}")

        return pipeline

    def validate_observation_dict(self, observation: Dict[str, Any]) -> np.ndarray:
        """
        Validate a single dictionary observation and return a 1D float64 numpy array
        ordered exactly according to the approved 16-feature schema.

        Parameters
        ----------
        observation : dict
            Key-value pairs representing the 16 features (and optional metadata).

        Returns
        -------
        np.ndarray
            1D array of shape (16,) with ordered float64 values (or np.nan).
        """
        if not isinstance(observation, dict):
            raise InputValidationError(f"Observation must be a dictionary, got {type(observation).__name__}")

        # Check for missing required features
        obs_keys = set(observation.keys())
        missing_features = [f for f in APPROVED_16_PREDICTORS if f not in obs_keys]
        if missing_features:
            raise InputValidationError(
                f"Missing {len(missing_features)} required feature(s): {missing_features}"
            )

        # Check for unknown keys (excluding permitted metadata keys)
        unknown_keys = obs_keys - set(APPROVED_16_PREDICTORS) - ALLOWED_METADATA_KEYS
        if unknown_keys:
            raise InputValidationError(
                f"Unexpected key(s) in observation: {sorted(list(unknown_keys))}. "
                f"Allowed features: {APPROVED_16_PREDICTORS}"
            )

        # Extract values in strict schema order and validate types/values
        feature_vector = []
        for feat in APPROVED_16_PREDICTORS:
            val = observation[feat]

            # Null / None / NaN handling
            if val is None or (isinstance(val, (float, np.floating)) and np.isnan(val)):
                feature_vector.append(np.nan)
                continue

            # Type validation: reject bool (subclass of int in Python)
            if isinstance(val, bool):
                raise InputValidationError(f"Invalid type for feature '{feat}': boolean is not allowed.")

            # Numeric conversion
            try:
                num_val = float(val)
            except (ValueError, TypeError) as e:
                raise InputValidationError(
                    f"Invalid non-numeric value for feature '{feat}': {val!r} ({type(val).__name__})"
                ) from e

            # Infinity validation
            if math.isinf(num_val) or np.isinf(num_val):
                raise InputValidationError(f"Infinity value detected in feature '{feat}': {val}")

            feature_vector.append(num_val)

        return np.array(feature_vector, dtype=np.float64)

    def predict_observation(
        self,
        observation: Union[Dict[str, Any], pd.Series],
        availability_timestamp: Optional[float] = None
    ) -> Dict[str, Any]:
        """
        Perform deterministic inference for a single observation window.

        Parameters
        ----------
        observation : dict or pd.Series
            Feature values for the 16 approved predictors.
        availability_timestamp : float, optional
            Timestamp (w_end) when the observation window closed.

        Returns
        -------
        dict
            Inference result containing probability, binary prediction, threshold,
            availability timestamp, model id and version.
        """
        if isinstance(observation, pd.Series):
            obs_dict = observation.to_dict()
        elif isinstance(observation, dict):
            obs_dict = observation.copy()
        else:
            raise InputValidationError(f"Observation must be dict or pd.Series, got {type(observation).__name__}")

        # Check if availability timestamp is embedded in observation dict
        if availability_timestamp is None:
            if "availability_timestamp" in obs_dict:
                availability_timestamp = float(obs_dict["availability_timestamp"])
            elif "window_end" in obs_dict:
                availability_timestamp = float(obs_dict["window_end"])

        # Validate and format input vector (1, 16)
        vec = self.validate_observation_dict(obs_dict)
        X = vec.reshape(1, -1)

        # Predict probability without modifying pipeline state
        probs = self.pipeline.predict_proba(X)
        positive_prob = float(probs[0, 1])
        binary_pred = int(positive_prob >= self.decision_threshold)

        return {
            "model_id": self.model_id,
            "model_version": self.model_version,
            "probability": positive_prob,
            "prediction": binary_pred,
            "decision_threshold": self.decision_threshold,
            "availability_timestamp": availability_timestamp,
            "temporal_availability_rule": "t_avail = w_end",
            "features_used": self.feature_names
        }

    def predict_batch(
        self,
        observations: Union[List[Dict[str, Any]], pd.DataFrame],
        availability_timestamps: Optional[List[float]] = None
    ) -> List[Dict[str, Any]]:
        """
        Perform deterministic batch inference for multiple observation windows.

        Parameters
        ----------
        observations : list of dicts or pd.DataFrame
            Multiple observation records.
        availability_timestamps : list of floats, optional
            List of availability timestamps corresponding to each observation.

        Returns
        -------
        list of dicts
            List of inference output dictionaries.
        """
        if isinstance(observations, pd.DataFrame):
            # Convert DataFrame to list of dicts without mutating original
            obs_list = observations.to_dict(orient="records")
        elif isinstance(observations, list):
            obs_list = [obs.copy() if isinstance(obs, dict) else obs for obs in observations]
        else:
            raise InputValidationError(f"Batch observations must be list of dicts or pd.DataFrame, got {type(observations).__name__}")

        if not obs_list:
            return []

        if availability_timestamps is not None and len(availability_timestamps) != len(obs_list):
            raise InputValidationError(
                f"Mismatch: {len(observations)} observations provided but {len(availability_timestamps)} timestamps."
            )

        results = []
        for idx, obs in enumerate(obs_list):
            ts = availability_timestamps[idx] if availability_timestamps is not None else None
            res = self.predict_observation(obs, availability_timestamp=ts)
            results.append(res)

        return results


def predict_window(
    observation: Dict[str, Any],
    model_path: Optional[str] = None,
    decision_threshold: float = DEFAULT_DECISION_THRESHOLD
) -> Dict[str, Any]:
    """Convenience functional wrapper for single-observation inference."""
    engine = ModelCInference(model_path=model_path, decision_threshold=decision_threshold)
    return engine.predict_observation(observation)
