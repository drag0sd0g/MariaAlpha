"""Tests for the FastAPI endpoints."""

from __future__ import annotations

from pathlib import Path

import joblib
import numpy as np
import pytest
from fastapi.testclient import TestClient
from lightgbm import LGBMClassifier
from sklearn.ensemble import RandomForestClassifier

from ml_signal.api.app import create_app
from ml_signal.config import Settings
from ml_signal.features.engine import FEATURE_NAMES, FeatureEngine
from ml_signal.features.regime_features import REGIME_FEATURE_NAMES
from ml_signal.model.regime_model import RegimeModel
from ml_signal.model.signal_model import SignalModel


def _make_regime_artifact(path: Path, version: str = "regime-v1") -> Path:
    rng = np.random.default_rng(11)
    X = rng.standard_normal((100, len(REGIME_FEATURE_NAMES)))
    y = rng.integers(1, 6, size=100)
    clf = RandomForestClassifier(n_estimators=10, max_depth=4, random_state=0)
    clf.fit(X, y)
    joblib.dump(
        {"model": clf, "version": version, "feature_names": REGIME_FEATURE_NAMES},
        path,
    )
    return path


@pytest.fixture
def client(settings: Settings, feature_engine: FeatureEngine, tmp_path: Path) -> TestClient:
    signal = SignalModel(str(tmp_path / "nonexistent.joblib"))
    regime = RegimeModel(str(tmp_path / "regime-nonexistent.joblib"))
    app = create_app(feature_engine, signal, regime, settings)
    return TestClient(app)


@pytest.fixture
def client_with_models(
    settings: Settings, feature_engine: FeatureEngine, tmp_path: Path
) -> TestClient:
    rng = np.random.default_rng(42)
    X = rng.standard_normal((200, 15))
    y = (X[:, 0] > 0).astype(int)
    clf = LGBMClassifier(n_estimators=10, num_leaves=4, verbose=-1)
    clf.fit(X, y)
    signal_path = tmp_path / "model.joblib"
    joblib.dump({"model": clf, "version": "v1", "feature_names": FEATURE_NAMES}, signal_path)
    regime_path = tmp_path / "regime.joblib"
    _make_regime_artifact(regime_path)

    signal = SignalModel(str(signal_path))
    regime = RegimeModel(str(regime_path))
    app = create_app(feature_engine, signal, regime, settings)
    return TestClient(app)


class TestHealth:
    def test_health_returns_200(self, client: TestClient) -> None:
        response = client.get("/health")
        assert response.status_code == 200
        assert response.json()["status"] == "healthy"


class TestReady:
    def test_not_ready_without_model_or_features(self, client: TestClient) -> None:
        response = client.get("/ready")
        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "not_ready"
        assert body["model_loaded"] is False
        assert body["regime_model_loaded"] is False

    def test_ready_with_model_and_features(
        self, client_with_models: TestClient, feature_engine: FeatureEngine
    ) -> None:
        # Inject a feature vector
        with feature_engine._lock:
            feature_engine._features["AAPL"] = {name: 0.0 for name in FEATURE_NAMES}
        response = client_with_models.get("/ready")
        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "ready"
        assert body["model_loaded"] is True
        assert body["regime_model_loaded"] is True


class TestMetrics:
    def test_metrics_returns_prometheus_format(self, client: TestClient) -> None:
        response = client.get("/metrics")
        assert response.status_code == 200
        assert "mariaalpha_ml" in response.text


class TestModelReload:
    def test_signal_reload_nonexistent_returns_failure(self, client: TestClient) -> None:
        response = client.post("/v1/models/reload", params={"path": "/nonexistent/model.joblib"})
        assert response.status_code == 200
        assert response.json()["success"] is False

    def test_signal_reload_valid_model(self, client: TestClient, tmp_path: Path) -> None:
        rng = np.random.default_rng(42)
        X = rng.standard_normal((100, 15))
        y = (X[:, 0] > 0).astype(int)
        clf = LGBMClassifier(n_estimators=5, num_leaves=4, verbose=-1)
        clf.fit(X, y)
        path = tmp_path / "new_model.joblib"
        joblib.dump({"model": clf, "version": "v2", "feature_names": FEATURE_NAMES}, path)

        response = client.post("/v1/models/reload", params={"path": str(path)})
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["current_version"] == "v2"

    def test_regime_reload_valid_model(self, client: TestClient, tmp_path: Path) -> None:
        path = tmp_path / "regime-v2.joblib"
        _make_regime_artifact(path, version="regime-v2")
        response = client.post(
            "/v1/models/reload",
            params={"path": str(path), "model": "regime"},
        )
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["current_version"] == "regime-v2"

    def test_regime_reload_nonexistent_returns_failure(self, client: TestClient) -> None:
        response = client.post(
            "/v1/models/reload",
            params={"path": "/nonexistent/regime.joblib", "model": "regime"},
        )
        assert response.status_code == 200
        assert response.json()["success"] is False

    def test_unknown_model_returns_400(self, client: TestClient) -> None:
        response = client.post("/v1/models/reload", params={"model": "garbage"})
        assert response.status_code == 400
        assert "unknown model" in response.json()["detail"]
