"""Tests for the gRPC SignalServicer."""

from __future__ import annotations

from concurrent import futures
from pathlib import Path

import grpc
import joblib
import numpy as np
import pytest
import signal_pb2
import signal_pb2_grpc
from lightgbm import LGBMClassifier
from sklearn.ensemble import RandomForestClassifier

from ml_signal.features.engine import FEATURE_NAMES, Bar, FeatureEngine
from ml_signal.features.regime_features import (
    MIN_BARS_FOR_REGIME,
    REGIME_FEATURE_NAMES,
)
from ml_signal.grpc_server.servicer import SignalServicer
from ml_signal.model.regime_model import (
    REGIME_HIGH_VOLATILITY,
    REGIME_LOW_VOLATILITY,
    REGIME_MEAN_REVERTING,
    REGIME_TRENDING_DOWN,
    REGIME_TRENDING_UP,
    RegimeModel,
)
from ml_signal.model.signal_model import SignalModel

_REGIME_LABELS = [
    REGIME_TRENDING_UP,
    REGIME_TRENDING_DOWN,
    REGIME_MEAN_REVERTING,
    REGIME_HIGH_VOLATILITY,
    REGIME_LOW_VOLATILITY,
]


@pytest.fixture
def trained_model(tmp_path: Path) -> SignalModel:
    rng = np.random.default_rng(42)
    X = rng.standard_normal((200, 15))
    y = (X[:, 0] > 0).astype(int)
    clf = LGBMClassifier(n_estimators=10, num_leaves=4, verbose=-1)
    clf.fit(X, y)
    path = tmp_path / "model.joblib"
    joblib.dump({"model": clf, "version": "test", "feature_names": FEATURE_NAMES}, path)
    return SignalModel(str(path))


@pytest.fixture
def regime_model(tmp_path: Path) -> RegimeModel:
    """Build a tiny regime model where each class is separable on feature 0."""
    rng = np.random.default_rng(7)
    rows: list[list[float]] = []
    labels: list[int] = []
    for cls_idx, label in enumerate(_REGIME_LABELS):
        for _ in range(60):
            row = [float(cls_idx) + rng.normal(0, 0.01)] + [
                float(rng.normal(0, 0.5)) for _ in range(len(REGIME_FEATURE_NAMES) - 1)
            ]
            rows.append(row)
            labels.append(label)
    X = np.array(rows)
    y = np.array(labels)
    clf = RandomForestClassifier(n_estimators=20, max_depth=4, random_state=0)
    clf.fit(X, y)
    path = tmp_path / "regime_model.joblib"
    joblib.dump(
        {
            "model": clf,
            "version": "regime-test",
            "feature_names": REGIME_FEATURE_NAMES,
        },
        path,
    )
    return RegimeModel(str(path))


@pytest.fixture
def grpc_channel(
    feature_engine: FeatureEngine,
    trained_model: SignalModel,
    regime_model: RegimeModel,
) -> grpc.Channel:
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
    servicer = SignalServicer(feature_engine, trained_model, regime_model)
    signal_pb2_grpc.add_SignalServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("[::]:0")
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    yield channel  # type: ignore[misc]
    server.stop(0)
    channel.close()


def _inject_bars(engine: FeatureEngine, symbol: str, closes: list[float]) -> None:
    """Populate the engine's bar history without going through the tick pipeline."""
    bars = [
        Bar(timestamp=float(i * 60), open=c, high=c, low=c, close=c, volume=1000)
        for i, c in enumerate(closes)
    ]
    with engine._lock:
        engine._bars[symbol] = bars


class TestGetSignal:
    def test_no_features_returns_neutral(self, grpc_channel: grpc.Channel) -> None:
        stub = signal_pb2_grpc.SignalServiceStub(grpc_channel)
        response = stub.GetSignal(signal_pb2.SignalRequest(symbol="AAPL"))
        assert response.symbol == "AAPL"
        assert response.direction == signal_pb2.NEUTRAL
        assert response.confidence == 0.0

    def test_with_features_returns_prediction(
        self, grpc_channel: grpc.Channel, feature_engine: FeatureEngine
    ) -> None:
        rng = np.random.default_rng(42)
        features = {name: float(rng.standard_normal()) for name in FEATURE_NAMES}
        with feature_engine._lock:
            feature_engine._features["AAPL"] = features

        stub = signal_pb2_grpc.SignalServiceStub(grpc_channel)
        response = stub.GetSignal(signal_pb2.SignalRequest(symbol="AAPL"))
        assert response.symbol == "AAPL"
        assert response.direction in (signal_pb2.NEUTRAL, signal_pb2.LONG, signal_pb2.SHORT)
        assert 0.0 <= response.confidence <= 1.0
        assert len(response.features) == 15


class TestGetRegime:
    def test_unknown_when_window_too_short(self, grpc_channel: grpc.Channel) -> None:
        """No bars yet → regime feature window unavailable → UNKNOWN."""
        stub = signal_pb2_grpc.SignalServiceStub(grpc_channel)
        response = stub.GetRegime(signal_pb2.RegimeRequest(symbol="AAPL"))
        assert response.symbol == "AAPL"
        assert response.regime == signal_pb2.UNKNOWN
        assert response.confidence == 0.0
        assert response.timestamp.seconds > 0

    def test_returns_classification_with_full_window(
        self, grpc_channel: grpc.Channel, feature_engine: FeatureEngine
    ) -> None:
        """When bars are present, GetRegime should return a non-UNKNOWN classification."""
        rng = np.random.default_rng(0)
        log_returns = rng.normal(loc=0.0, scale=0.002, size=MIN_BARS_FOR_REGIME + 10)
        closes = (100.0 * np.exp(np.cumsum(log_returns))).tolist()
        _inject_bars(feature_engine, "AAPL", closes)

        stub = signal_pb2_grpc.SignalServiceStub(grpc_channel)
        response = stub.GetRegime(signal_pb2.RegimeRequest(symbol="AAPL"))
        assert response.symbol == "AAPL"
        assert response.regime in {
            signal_pb2.TRENDING_UP,
            signal_pb2.TRENDING_DOWN,
            signal_pb2.MEAN_REVERTING,
            signal_pb2.HIGH_VOLATILITY,
            signal_pb2.LOW_VOLATILITY,
        }
        assert 0.0 <= response.confidence <= 1.0
        assert response.timestamp.seconds > 0
