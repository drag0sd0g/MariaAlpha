"""Random Forest regime classifier with hot-reload support.

Per TDD §5.2.3, the regime model takes 8 statistical features and returns one of
five MarketRegime labels plus a confidence score (the predicted class's
probability). Hot-reload semantics mirror SignalModel: the model pointer is
swapped atomically under a lock; readers never see partial state.

Regime label values match the MarketRegime enum in signal.proto.
"""

from __future__ import annotations

import threading
import time
from typing import Any

import joblib
import structlog

from ml_signal.features.regime_features import REGIME_FEATURE_NAMES
from ml_signal.metrics import INFERENCE_DURATION, REGIME_MODEL_INFO

logger = structlog.get_logger()

REGIME_UNKNOWN = 0
REGIME_TRENDING_UP = 1
REGIME_TRENDING_DOWN = 2
REGIME_MEAN_REVERTING = 3
REGIME_HIGH_VOLATILITY = 4
REGIME_LOW_VOLATILITY = 5

REGIME_NAMES: dict[int, str] = {
    REGIME_UNKNOWN: "UNKNOWN",
    REGIME_TRENDING_UP: "TRENDING_UP",
    REGIME_TRENDING_DOWN: "TRENDING_DOWN",
    REGIME_MEAN_REVERTING: "MEAN_REVERTING",
    REGIME_HIGH_VOLATILITY: "HIGH_VOLATILITY",
    REGIME_LOW_VOLATILITY: "LOW_VOLATILITY",
}


class RegimeModel:
    """Wraps a scikit-learn RandomForestClassifier with thread-safe predict + reload."""

    def __init__(self, model_path: str) -> None:
        self._model: Any = None
        self._model_path = model_path
        self._version = "none"
        self._feature_names: list[str] = list(REGIME_FEATURE_NAMES)
        self._class_labels: list[int] = []
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

    def predict(self, features: dict[str, float]) -> tuple[int, float]:
        """Predict the current regime and its confidence.

        Returns (regime_label, confidence). Confidence is the predicted class's
        probability, in [0, 1]. If the model isn't loaded, returns
        (REGIME_UNKNOWN, 0.0) so the caller can fall back gracefully.
        """
        with self._lock:
            if self._model is None or not self._class_labels:
                return REGIME_UNKNOWN, 0.0
            feature_names = list(self._feature_names)

        import numpy as np

        feature_array = np.array(
            [[features.get(name, 0.0) for name in feature_names]],
            dtype=np.float64,
        )

        with INFERENCE_DURATION.labels(model="regime").time(), self._lock:
            if self._model is None or not self._class_labels:
                return REGIME_UNKNOWN, 0.0
            proba = self._model.predict_proba(feature_array)[0]
            class_labels = list(self._class_labels)

        best_idx = int(np.argmax(proba))
        regime_label = int(class_labels[best_idx])
        confidence = float(proba[best_idx])
        return regime_label, confidence

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
            model = data["model"]
            class_labels = [int(c) for c in getattr(model, "classes_", [])]
            with self._lock:
                self._model = model
                self._version = str(data.get("version", "unknown"))
                self._feature_names = data.get("feature_names", list(REGIME_FEATURE_NAMES))
                self._class_labels = class_labels
                self._loaded_at = time.time()
            REGIME_MODEL_INFO.info({"version": self._version, "path": path})
            logger.info(
                "regime_model_loaded",
                version=self._version,
                path=path,
                classes=class_labels,
            )
            return True
        except FileNotFoundError:
            logger.warning("regime_model_not_found", path=path)
            return False
        except Exception:
            logger.exception("regime_model_load_failed", path=path)
            return False
