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

from ml_signal.features.engine import FEATURE_NAMES, FeatureEngine
from ml_signal.grpc_server.servicer import SignalServicer
from ml_signal.model.signal_model import SignalModel


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
def grpc_channel(feature_engine: FeatureEngine, trained_model: SignalModel) -> grpc.Channel:
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
    servicer = SignalServicer(feature_engine, trained_model)
    signal_pb2_grpc.add_SignalServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("[::]:0")
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    yield channel  # type: ignore[misc]
    server.stop(0)
    channel.close()


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
        # Inject features directly (bypass tick aggregation)
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
    def test_returns_unknown(self, grpc_channel: grpc.Channel) -> None:
        stub = signal_pb2_grpc.SignalServiceStub(grpc_channel)
        response = stub.GetRegime(signal_pb2.RegimeRequest(symbol="AAPL"))
        assert response.regime == signal_pb2.UNKNOWN
        assert response.confidence == 0.0
