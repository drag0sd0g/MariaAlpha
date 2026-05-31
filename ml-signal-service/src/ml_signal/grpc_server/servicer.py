"""gRPC SignalService implementation."""

from __future__ import annotations

import queue
import time
from typing import TYPE_CHECKING

import signal_pb2
import signal_pb2_grpc
import structlog
from google.protobuf.timestamp_pb2 import Timestamp

from ml_signal.features.regime_features import compute_regime_features
from ml_signal.metrics import (
    GRPC_REQUESTS,
    REGIME_CONFIDENCE,
    REGIME_PREDICTIONS,
    STREAM_CLIENTS_ACTIVE,
)
from ml_signal.model.regime_model import (
    REGIME_HIGH_VOLATILITY,
    REGIME_LOW_VOLATILITY,
    REGIME_MEAN_REVERTING,
    REGIME_NAMES,
    REGIME_TRENDING_DOWN,
    REGIME_TRENDING_UP,
    REGIME_UNKNOWN,
)
from ml_signal.model.signal_model import DIRECTION_LONG, DIRECTION_NEUTRAL, DIRECTION_SHORT

if TYPE_CHECKING:
    from collections.abc import Iterator

    import grpc

    from ml_signal.features.engine import FeatureEngine
    from ml_signal.model.regime_model import RegimeModel
    from ml_signal.model.signal_model import SignalModel

logger = structlog.get_logger()

_DIRECTION_MAP = {
    DIRECTION_NEUTRAL: signal_pb2.NEUTRAL,
    DIRECTION_LONG: signal_pb2.LONG,
    DIRECTION_SHORT: signal_pb2.SHORT,
}

_REGIME_MAP = {
    REGIME_UNKNOWN: signal_pb2.UNKNOWN,
    REGIME_TRENDING_UP: signal_pb2.TRENDING_UP,
    REGIME_TRENDING_DOWN: signal_pb2.TRENDING_DOWN,
    REGIME_MEAN_REVERTING: signal_pb2.MEAN_REVERTING,
    REGIME_HIGH_VOLATILITY: signal_pb2.HIGH_VOLATILITY,
    REGIME_LOW_VOLATILITY: signal_pb2.LOW_VOLATILITY,
}


class SignalServicer(signal_pb2_grpc.SignalServiceServicer):  # type: ignore[misc]
    """Serves GetSignal, GetRegime, and StreamSignals RPCs."""

    def __init__(
        self,
        feature_engine: FeatureEngine,
        signal_model: SignalModel,
        regime_model: RegimeModel,
    ) -> None:
        self._feature_engine = feature_engine
        self._signal_model = signal_model
        self._regime_model = regime_model

    def GetSignal(
        self,
        request: signal_pb2.SignalRequest,
        context: grpc.ServicerContext,
    ) -> signal_pb2.SignalResponse:
        GRPC_REQUESTS.labels(method="GetSignal").inc()

        symbol = request.symbol
        features = self._feature_engine.get_features(symbol)

        if features is None:
            return signal_pb2.SignalResponse(
                symbol=symbol,
                direction=signal_pb2.NEUTRAL,
                confidence=0.0,
                recommended_position_size=0.0,
            )

        direction, confidence, position_size, used_features = self._signal_model.predict(features)

        now = Timestamp()
        now.FromSeconds(int(time.time()))

        return signal_pb2.SignalResponse(
            symbol=symbol,
            direction=_DIRECTION_MAP.get(direction, signal_pb2.NEUTRAL),
            confidence=confidence,
            recommended_position_size=position_size,
            timestamp=now,
            features=used_features,
        )

    def GetRegime(
        self,
        request: signal_pb2.RegimeRequest,
        context: grpc.ServicerContext,
    ) -> signal_pb2.RegimeResponse:
        """Classify the current market regime for the requested symbol.

        Returns UNKNOWN with confidence 0 when either the rolling bar window is
        too short to compute features or the regime model isn't loaded. This
        mirrors GetSignal's degrade-gracefully contract.
        """
        GRPC_REQUESTS.labels(method="GetRegime").inc()
        symbol = request.symbol

        now = Timestamp()
        now.FromSeconds(int(time.time()))

        bars = self._feature_engine.get_bars(symbol)
        regime_features = compute_regime_features(bars)
        if regime_features is None:
            REGIME_PREDICTIONS.labels(symbol=symbol, regime="UNKNOWN").inc()
            return signal_pb2.RegimeResponse(
                symbol=symbol,
                regime=signal_pb2.UNKNOWN,
                confidence=0.0,
                timestamp=now,
            )

        regime_label, confidence = self._regime_model.predict(regime_features)
        regime_name = REGIME_NAMES.get(regime_label, "UNKNOWN")
        REGIME_PREDICTIONS.labels(symbol=symbol, regime=regime_name).inc()
        REGIME_CONFIDENCE.observe(confidence)

        return signal_pb2.RegimeResponse(
            symbol=symbol,
            regime=_REGIME_MAP.get(regime_label, signal_pb2.UNKNOWN),
            confidence=confidence,
            timestamp=now,
        )

    def StreamSignals(
        self,
        request: signal_pb2.SignalStreamRequest,
        context: grpc.ServicerContext,
    ) -> Iterator[signal_pb2.SignalResponse]:
        """Server-streaming RPC: pushes signals on every feature update."""
        GRPC_REQUESTS.labels(method="StreamSignals").inc()
        STREAM_CLIENTS_ACTIVE.inc()

        symbols: set[str] | None = set(request.symbols) if request.symbols else None
        q: queue.Queue[tuple[str, dict[str, float]]] = queue.Queue(maxsize=100)
        self._feature_engine.add_listener(q, symbols)

        logger.info("stream_client_connected", symbols=list(symbols) if symbols else "all")

        try:
            while context.is_active():
                try:
                    symbol, features = q.get(timeout=1.0)
                except queue.Empty:
                    continue

                direction, confidence, position_size, used_features = self._signal_model.predict(
                    features
                )

                now = Timestamp()
                now.FromSeconds(int(time.time()))

                yield signal_pb2.SignalResponse(
                    symbol=symbol,
                    direction=_DIRECTION_MAP.get(direction, signal_pb2.NEUTRAL),
                    confidence=confidence,
                    recommended_position_size=position_size,
                    timestamp=now,
                    features=used_features,
                )
        finally:
            self._feature_engine.remove_listener(q)
            STREAM_CLIENTS_ACTIVE.dec()
            logger.info("stream_client_disconnected")
