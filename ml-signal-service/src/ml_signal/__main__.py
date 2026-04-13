"""ML Signal Service entry point.

Starts three concurrent components:
1. FastAPI (uvicorn) — main thread
2. gRPC server — background thread pool
3. Kafka consumer — background daemon thread
"""

from __future__ import annotations

import sys
import threading
from concurrent import futures
from pathlib import Path

import grpc
import structlog
import uvicorn

# Add proto generated stubs to path (for local development)
_proto_path = (
    Path(__file__).resolve().parent.parent.parent.parent / "proto" / "generated" / "python"
)
if _proto_path.exists() and str(_proto_path) not in sys.path:
    sys.path.insert(0, str(_proto_path))

import signal_pb2_grpc  # noqa: E402 — must come after sys.path setup

from ml_signal.api.app import create_app  # noqa: E402
from ml_signal.config import Settings  # noqa: E402
from ml_signal.consumer.tick_consumer import TickConsumer  # noqa: E402
from ml_signal.features.engine import FeatureEngine  # noqa: E402
from ml_signal.grpc_server.servicer import SignalServicer  # noqa: E402
from ml_signal.model.signal_model import SignalModel  # noqa: E402

logger = structlog.get_logger()


def main() -> None:
    settings = Settings()
    logger.info(
        "starting_ml_signal_service",
        grpc_port=settings.grpc_port,
        api_port=settings.api_port,
        model_path=settings.signal_model_path,
    )

    # Core components
    feature_engine = FeatureEngine(settings)
    signal_model = SignalModel(settings.signal_model_path)

    # Kafka consumer (daemon thread — dies when main thread exits)
    consumer = TickConsumer(settings, feature_engine)
    consumer_thread = threading.Thread(target=consumer.run, name="tick-consumer", daemon=True)
    consumer_thread.start()

    # gRPC server
    grpc_server = grpc.server(futures.ThreadPoolExecutor(max_workers=settings.grpc_max_workers))
    servicer = SignalServicer(feature_engine, signal_model)
    signal_pb2_grpc.add_SignalServiceServicer_to_server(servicer, grpc_server)
    grpc_server.add_insecure_port(f"[::]:{settings.grpc_port}")
    grpc_server.start()
    logger.info("grpc_server_started", port=settings.grpc_port)

    # FastAPI (blocks on main thread)
    app = create_app(feature_engine, signal_model, settings)
    uvicorn.run(app, host="0.0.0.0", port=settings.api_port, log_level="info")


if __name__ == "__main__":
    main()
