"""Tests for the StreamSignals gRPC endpoint."""

from __future__ import annotations

import threading
import time
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
def model_and_engine(settings, tmp_path: Path) -> tuple[SignalModel, FeatureEngine]:
    rng = np.random.default_rng(42)
    X = rng.standard_normal((200, 15))
    y = (X[:, 0] > 0).astype(int)
    clf = LGBMClassifier(n_estimators=10, num_leaves=4, verbose=-1)
    clf.fit(X, y)
    path = tmp_path / "model.joblib"
    joblib.dump({"model": clf, "version": "test", "feature_names": FEATURE_NAMES}, path)
    return SignalModel(str(path)), FeatureEngine(settings)


@pytest.fixture
def stream_setup(model_and_engine: tuple[SignalModel, FeatureEngine]):
    signal_model, feature_engine = model_and_engine
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    servicer = SignalServicer(feature_engine, signal_model)
    signal_pb2_grpc.add_SignalServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("[::]:0")
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    yield channel, feature_engine
    server.stop(0)
    channel.close()


class TestStreamSignals:
    def test_receives_signal_on_feature_update(self, stream_setup) -> None:
        channel, feature_engine = stream_setup
        stub = signal_pb2_grpc.SignalServiceStub(channel)

        responses: list[signal_pb2.SignalResponse] = []

        def stream_reader():
            request = signal_pb2.SignalStreamRequest(symbols=["AAPL"])
            try:
                for resp in stub.StreamSignals(request, timeout=5):
                    responses.append(resp)
                    break  # one response is enough
            except grpc.RpcError:
                pass

        reader = threading.Thread(target=stream_reader, daemon=True)
        reader.start()

        # Give the stream time to register
        time.sleep(0.3)

        # Trigger a feature update by injecting directly
        features = {name: 0.0 for name in FEATURE_NAMES}
        with feature_engine._lock:
            feature_engine._features["AAPL"] = features
        feature_engine._notify_listeners("AAPL", features)

        reader.join(timeout=5)
        assert len(responses) == 1
        assert responses[0].symbol == "AAPL"

    def test_stream_filters_by_symbol(self, stream_setup) -> None:
        channel, feature_engine = stream_setup
        stub = signal_pb2_grpc.SignalServiceStub(channel)

        responses: list[signal_pb2.SignalResponse] = []

        def stream_reader():
            request = signal_pb2.SignalStreamRequest(symbols=["MSFT"])
            try:
                for resp in stub.StreamSignals(request, timeout=3):
                    responses.append(resp)
            except grpc.RpcError:
                pass

        reader = threading.Thread(target=stream_reader, daemon=True)
        reader.start()

        time.sleep(0.3)

        # Push AAPL features (should NOT reach the MSFT-only stream)
        features = {name: 0.0 for name in FEATURE_NAMES}
        feature_engine._notify_listeners("AAPL", features)

        reader.join(timeout=4)
        assert len(responses) == 0  # MSFT filter should exclude AAPL
