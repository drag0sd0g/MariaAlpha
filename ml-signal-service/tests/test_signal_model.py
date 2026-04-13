"""Tests for the SignalModel."""

from __future__ import annotations

from pathlib import Path

import joblib
import numpy as np
import pytest
from lightgbm import LGBMClassifier

from ml_signal.features.engine import FEATURE_NAMES
from ml_signal.model.signal_model import (
    DIRECTION_LONG,
    DIRECTION_NEUTRAL,
    DIRECTION_SHORT,
    SignalModel,
)


@pytest.fixture
def trained_model_path(tmp_path: Path) -> Path:
    """Train a tiny LightGBM model and save it."""
    rng = np.random.default_rng(42)
    X = rng.standard_normal((200, 15))
    y = (X[:, 0] > 0).astype(int)  # simple rule: feature 0 > 0 → class 1

    clf = LGBMClassifier(n_estimators=10, num_leaves=4, verbose=-1)
    clf.fit(X, y)

    path = tmp_path / "test_model.joblib"
    joblib.dump(
        {"model": clf, "version": "test-v1", "feature_names": FEATURE_NAMES},
        path,
    )
    return path


class TestSignalModel:
    def test_no_model_returns_neutral(self, tmp_path: Path) -> None:
        model = SignalModel(str(tmp_path / "nonexistent.joblib"))
        direction, confidence, pos_size, _ = model.predict({name: 0.0 for name in FEATURE_NAMES})
        assert direction == DIRECTION_NEUTRAL
        assert confidence == 0.0
        assert pos_size == 0.0
        assert not model.is_loaded

    def test_predict_with_model(self, trained_model_path: Path) -> None:
        model = SignalModel(str(trained_model_path))
        assert model.is_loaded

        # Feature 0 strongly positive → model should predict LONG (class 1)
        features = {name: 0.0 for name in FEATURE_NAMES}
        features[FEATURE_NAMES[0]] = 5.0  # strong positive
        direction, confidence, pos_size, _ = model.predict(features)
        assert direction in (DIRECTION_LONG, DIRECTION_NEUTRAL, DIRECTION_SHORT)
        assert 0.0 <= confidence <= 1.0
        assert 0.0 <= pos_size <= 0.10

    def test_version_reported(self, trained_model_path: Path) -> None:
        model = SignalModel(str(trained_model_path))
        assert model.version == "test-v1"

    def test_reload_replaces_model(self, tmp_path: Path, trained_model_path: Path) -> None:
        model = SignalModel(str(tmp_path / "nonexistent.joblib"))
        assert not model.is_loaded

        result = model.reload(str(trained_model_path))
        assert result["success"] is True
        assert result["current_version"] == "test-v1"
        assert model.is_loaded

    def test_reload_nonexistent_returns_failure(self, tmp_path: Path) -> None:
        model = SignalModel(str(tmp_path / "nonexistent.joblib"))
        result = model.reload(str(tmp_path / "also_nonexistent.joblib"))
        assert result["success"] is False
