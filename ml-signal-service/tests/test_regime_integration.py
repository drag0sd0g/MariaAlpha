"""Integration test: GetRegime over a real gRPC channel with the production model.

This test exercises the full pipeline — gRPC servicer → FeatureEngine bar
snapshot → regime feature extractor → real RandomForest trained by
`scripts/train_regime_model.py` — and verifies that synthetic price paths drawn
from each regime are classified correctly by the deployed model artifact.

If `ml-models/regime_model.joblib` is absent (clean checkout that hasn't run the
training script yet) the entire module is skipped so unit tests still pass on
fresh clones.
"""

from __future__ import annotations

from concurrent import futures
from pathlib import Path

import grpc
import numpy as np
import pytest
import signal_pb2
import signal_pb2_grpc

from ml_signal.config import Settings
from ml_signal.features.engine import Bar, FeatureEngine
from ml_signal.features.regime_features import MIN_BARS_FOR_REGIME
from ml_signal.grpc_server.servicer import SignalServicer
from ml_signal.model.regime_model import RegimeModel
from ml_signal.model.signal_model import SignalModel

_REGIME_MODEL_PATH = Path(__file__).resolve().parents[2] / "ml-models" / "regime_model.joblib"


pytestmark = pytest.mark.skipif(
    not _REGIME_MODEL_PATH.exists(),
    reason=(
        "ml-models/regime_model.joblib not present — run "
        "`python scripts/train_regime_model.py` to generate the artifact"
    ),
)


@pytest.fixture
def engine_with_real_models(tmp_path: Path) -> tuple[FeatureEngine, RegimeModel, SignalModel]:
    settings = Settings(
        signal_model_path=str(tmp_path / "no-signal.joblib"),
        regime_model_path=str(_REGIME_MODEL_PATH),
        min_bars_for_features=5,
    )
    feature_engine = FeatureEngine(settings)
    signal_model = SignalModel(settings.signal_model_path)  # ok if unloaded
    regime_model = RegimeModel(settings.regime_model_path)
    assert regime_model.is_loaded, "production regime model must load"
    return feature_engine, regime_model, signal_model


@pytest.fixture
def grpc_stub(
    engine_with_real_models: tuple[FeatureEngine, RegimeModel, SignalModel],
) -> tuple[signal_pb2_grpc.SignalServiceStub, FeatureEngine]:
    feature_engine, regime_model, signal_model = engine_with_real_models
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
    servicer = SignalServicer(feature_engine, signal_model, regime_model)
    signal_pb2_grpc.add_SignalServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("[::]:0")
    server.start()
    channel = grpc.insecure_channel(f"localhost:{port}")
    stub = signal_pb2_grpc.SignalServiceStub(channel)
    yield stub, feature_engine  # type: ignore[misc]
    server.stop(0)
    channel.close()


def _inject_path(engine: FeatureEngine, symbol: str, closes: np.ndarray) -> None:
    bars = []
    for i, c in enumerate(closes):
        p = float(c)
        bars.append(Bar(timestamp=float(i * 60), open=p, high=p, low=p, close=p, volume=1000))
    with engine._lock:
        engine._bars[symbol] = bars


def _trending_path(drift: float, seed: int, n: int = MIN_BARS_FOR_REGIME + 20) -> np.ndarray:
    rng = np.random.default_rng(seed)
    log_returns = rng.normal(loc=drift, scale=0.002, size=n - 1)
    return 100.0 * np.exp(np.concatenate([[0.0], np.cumsum(log_returns)]))


def _gbm_path(sigma: float, seed: int, n: int = MIN_BARS_FOR_REGIME + 20) -> np.ndarray:
    rng = np.random.default_rng(seed)
    log_returns = rng.normal(loc=0.0, scale=sigma, size=n - 1)
    return 100.0 * np.exp(np.concatenate([[0.0], np.cumsum(log_returns)]))


class TestRegimeClassificationAccuracy:
    """End-to-end accuracy on held-out synthetic paths.

    We don't require 100% recovery (the classifier is a probabilistic model),
    just majority-correct over a small batch — enough to detect feature/model
    drift but tolerant of class-boundary noise.
    """

    def test_trending_up_paths(self, grpc_stub) -> None:
        stub, engine = grpc_stub
        correct = 0
        n_trials = 10
        for seed in range(n_trials):
            closes = _trending_path(drift=0.002, seed=seed)
            _inject_path(engine, "AAPL", closes)
            response = stub.GetRegime(signal_pb2.RegimeRequest(symbol="AAPL"))
            if response.regime == signal_pb2.TRENDING_UP:
                correct += 1
        assert correct >= 7, f"expected ≥7/10 TRENDING_UP, got {correct}"

    def test_trending_down_paths(self, grpc_stub) -> None:
        stub, engine = grpc_stub
        correct = 0
        n_trials = 10
        for seed in range(n_trials):
            closes = _trending_path(drift=-0.002, seed=seed + 100)
            _inject_path(engine, "MSFT", closes)
            response = stub.GetRegime(signal_pb2.RegimeRequest(symbol="MSFT"))
            if response.regime == signal_pb2.TRENDING_DOWN:
                correct += 1
        assert correct >= 7, f"expected ≥7/10 TRENDING_DOWN, got {correct}"

    def test_high_volatility_paths(self, grpc_stub) -> None:
        stub, engine = grpc_stub
        correct = 0
        n_trials = 10
        for seed in range(n_trials):
            closes = _gbm_path(sigma=0.020, seed=seed + 200)
            _inject_path(engine, "TSLA", closes)
            response = stub.GetRegime(signal_pb2.RegimeRequest(symbol="TSLA"))
            if response.regime == signal_pb2.HIGH_VOLATILITY:
                correct += 1
        assert correct >= 8, f"expected ≥8/10 HIGH_VOLATILITY, got {correct}"

    def test_low_volatility_paths(self, grpc_stub) -> None:
        stub, engine = grpc_stub
        correct = 0
        n_trials = 10
        for seed in range(n_trials):
            closes = _gbm_path(sigma=0.0005, seed=seed + 300)
            _inject_path(engine, "JNJ", closes)
            response = stub.GetRegime(signal_pb2.RegimeRequest(symbol="JNJ"))
            if response.regime == signal_pb2.LOW_VOLATILITY:
                correct += 1
        assert correct >= 8, f"expected ≥8/10 LOW_VOLATILITY, got {correct}"

    def test_confidence_in_valid_range(self, grpc_stub) -> None:
        stub, engine = grpc_stub
        closes = _trending_path(drift=0.002, seed=999)
        _inject_path(engine, "SPY", closes)
        response = stub.GetRegime(signal_pb2.RegimeRequest(symbol="SPY"))
        assert 0.0 <= response.confidence <= 1.0
        # A clearly trending path should yield reasonable confidence (>1/5 baseline).
        assert response.confidence > 0.3
