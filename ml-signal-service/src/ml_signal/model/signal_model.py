"""LightGBM signal model wrapper with hot-reload support."""

from __future__ import annotations

import threading
import time
from typing import Any

import joblib
import numpy as np
import structlog

from ml_signal.features.engine import FEATURE_NAMES
from ml_signal.metrics import INFERENCE_DURATION, MODEL_INFO

logger = structlog.get_logger()

# Direction constants matching proto enum values
DIRECTION_NEUTRAL = 0
DIRECTION_LONG = 1
DIRECTION_SHORT = 2


class SignalModel:
    """Wraps a LightGBM classifier with thread-safe predict and reload."""

    def __init__(self, model_path: str) -> None:
        self._model: Any = None
        self._model_path = model_path
        self._version = "none"
        self._feature_names: list[str] = list(FEATURE_NAMES)
        self._loaded_at: float = 0.0
        self._lock = threading.Lock()
        self._load(model_path)

    @property
    def is_loaded(self) -> bool:
        with self._lock:
            return self._model is not None

    @property
    def version(self) -> str:
        with self._lock:
            return self._version

    def predict(self, features: dict[str, float]) -> tuple[int, float, float, dict[str, float]]:
        """Predict direction, confidence, and position size.

        Returns (direction, confidence, position_size, features_used).
        Direction values: 0=NEUTRAL, 1=LONG, 2=SHORT.
        """
        with self._lock:
            if self._model is None:
                return DIRECTION_NEUTRAL, 0.0, 0.0, features

        # Build feature array in the correct order
        feature_array = np.array(
            [[features.get(name, 0.0) for name in self._feature_names]],
            dtype=np.float64,
        )

        with INFERENCE_DURATION.labels(model="signal").time(), self._lock:
            if self._model is None:
                return DIRECTION_NEUTRAL, 0.0, 0.0, features
            proba = self._model.predict_proba(feature_array)[0]

        # proba[1] = P(positive return)
        prob_up = float(proba[1])

        if prob_up > 0.55:
            direction = DIRECTION_LONG
            confidence = prob_up
        elif prob_up < 0.45:
            direction = DIRECTION_SHORT
            confidence = 1.0 - prob_up
        else:
            direction = DIRECTION_NEUTRAL
            confidence = 0.5

        # Position size: scale with confidence, max 10% of capital
        position_size = min(0.10, max(0.0, (confidence - 0.5) * 0.2))

        return direction, confidence, position_size, features

    def reload(self, path: str | None = None) -> dict[str, Any]:
        """Hot-reload a model from disk. Returns metadata about the operation."""
        path = path or self._model_path
        old_version = self._version
        success = self._load(path)
        return {
            "success": success,
            "previous_version": old_version,
            "current_version": self._version,
            "model_path": path,
        }

    def _load(self, path: str) -> bool:
        try:
            data = joblib.load(path)
            with self._lock:
                self._model = data["model"]
                self._version = str(data.get("version", "unknown"))
                self._feature_names = data.get("feature_names", list(FEATURE_NAMES))
                self._loaded_at = time.time()
            MODEL_INFO.info({"version": self._version, "path": path})
            logger.info("model_loaded", version=self._version, path=path)
            return True
        except FileNotFoundError:
            logger.warning("model_not_found", path=path)
            return False
        except Exception:
            logger.exception("model_load_failed", path=path)
            return False
