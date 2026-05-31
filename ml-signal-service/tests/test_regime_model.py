"""Tests for the RegimeModel wrapper."""

from __future__ import annotations

from pathlib import Path

import joblib
import numpy as np
import pytest
from sklearn.ensemble import RandomForestClassifier

from ml_signal.features.regime_features import REGIME_FEATURE_NAMES
from ml_signal.model.regime_model import (
    REGIME_HIGH_VOLATILITY,
    REGIME_LOW_VOLATILITY,
    REGIME_MEAN_REVERTING,
    REGIME_NAMES,
    REGIME_TRENDING_DOWN,
    REGIME_TRENDING_UP,
    REGIME_UNKNOWN,
    RegimeModel,
)

_TRAINING_LABELS = [
    REGIME_TRENDING_UP,
    REGIME_TRENDING_DOWN,
    REGIME_MEAN_REVERTING,
    REGIME_HIGH_VOLATILITY,
    REGIME_LOW_VOLATILITY,
]


@pytest.fixture
def trained_model_path(tmp_path: Path) -> Path:
    """Build a tiny RandomForest where each class is trivially separable on feature 0.

    Centroids are spaced 10 units apart and the other features carry no signal, so
    every class is recoverable from feature 0 alone — this lets us validate the
    label-mapping plumbing without depending on a real model's accuracy.
    """
    rng = np.random.default_rng(7)
    rows: list[list[float]] = []
    labels: list[int] = []
    for cls_idx, label in enumerate(_TRAINING_LABELS):
        for _ in range(80):
            row = [float(cls_idx) * 10.0 + rng.normal(0, 0.05)] + [
                0.0 for _ in range(len(REGIME_FEATURE_NAMES) - 1)
            ]
            rows.append(row)
            labels.append(label)

    X = np.array(rows)
    y = np.array(labels)
    clf = RandomForestClassifier(n_estimators=50, max_depth=4, random_state=0)
    clf.fit(X, y)

    path = tmp_path / "regime_model.joblib"
    joblib.dump(
        {
            "model": clf,
            "version": "regime-test-v1",
            "feature_names": REGIME_FEATURE_NAMES,
        },
        path,
    )
    return path


class TestRegimeModel:
    def test_no_model_returns_unknown(self, tmp_path: Path) -> None:
        model = RegimeModel(str(tmp_path / "nonexistent.joblib"))
        regime, confidence = model.predict({name: 0.0 for name in REGIME_FEATURE_NAMES})
        assert regime == REGIME_UNKNOWN
        assert confidence == 0.0
        assert not model.is_loaded

    def test_predict_with_model_returns_known_label(self, trained_model_path: Path) -> None:
        model = RegimeModel(str(trained_model_path))
        assert model.is_loaded

        # Feature 0 ≈ 0 is the TRENDING_UP centroid in the fixture (cls_idx == 0).
        features = {name: 0.0 for name in REGIME_FEATURE_NAMES}
        features[REGIME_FEATURE_NAMES[0]] = 0.0
        regime, confidence = model.predict(features)
        assert regime == REGIME_TRENDING_UP
        assert 0.0 <= confidence <= 1.0

    def test_predicts_each_training_class(self, trained_model_path: Path) -> None:
        """Hitting each training centroid should recover the corresponding label."""
        model = RegimeModel(str(trained_model_path))
        for cls_idx, expected_label in enumerate(_TRAINING_LABELS):
            features = {name: 0.0 for name in REGIME_FEATURE_NAMES}
            features[REGIME_FEATURE_NAMES[0]] = float(cls_idx) * 10.0
            regime, _ = model.predict(features)
            expected = REGIME_NAMES[expected_label]
            got = REGIME_NAMES[regime]
            assert regime == expected_label, f"cls_idx={cls_idx} expected {expected} got {got}"

    def test_version_reported(self, trained_model_path: Path) -> None:
        model = RegimeModel(str(trained_model_path))
        assert model.version == "regime-test-v1"

    def test_reload_replaces_model(
        self, tmp_path: Path, trained_model_path: Path
    ) -> None:
        model = RegimeModel(str(tmp_path / "nonexistent.joblib"))
        assert not model.is_loaded

        result = model.reload(str(trained_model_path))
        assert result["success"] is True
        assert result["current_version"] == "regime-test-v1"
        assert model.is_loaded

    def test_reload_nonexistent_returns_failure(self, tmp_path: Path) -> None:
        model = RegimeModel(str(tmp_path / "nonexistent.joblib"))
        result = model.reload(str(tmp_path / "still_nonexistent.joblib"))
        assert result["success"] is False
        assert not model.is_loaded
