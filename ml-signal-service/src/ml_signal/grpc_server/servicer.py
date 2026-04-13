"""gRPC SignalService implementation."""

from __future__ import annotations

import queue
import time
from typing import TYPE_CHECKING

import signal_pb2
import signal_pb2_grpc
import structlog
from google.protobuf.timestamp_pb2 import Timestamp

from ml_signal.metrics import GRPC_REQUESTS, STREAM_CLIENTS_ACTIVE
from ml_signal.model.signal_model import DIRECTION_LONG, DIRECTION_NEUTRAL, DIRECTION_SHORT

if TYPE_CHECKING:
    from collections.abc import Iterator

    import grpc

    from ml_signal.features.engine import FeatureEngine
    from ml_signal.model.signal_model import SignalModel

logger = structlog.get_logger()

_DIRECTION_MAP = {
    DIRECTION_NEUTRAL: signal_pb2.NEUTRAL,
    DIRECTION_LONG: signal_pb2.LONG,
    DIRECTION_SHORT: signal_pb2.SHORT,
}


class SignalServicer(signal_pb2_grpc.SignalServiceServicer):  # type: ignore[misc]
    """Serves GetSignal, GetRegime, and StreamSignals RPCs."""

    def __init__(self, feature_engine: FeatureEngine, signal_model: SignalModel) -> None:
        self._feature_engine = feature_engine
        self._signal_model = signal_model

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
        """Stub — returns UNKNOWN. Regime classifier is Phase 2 (issue 2.3.1)."""
        GRPC_REQUESTS.labels(method="GetRegime").inc()
        return signal_pb2.RegimeResponse(
            symbol=request.symbol,
            regime=signal_pb2.UNKNOWN,
            confidence=0.0,
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
