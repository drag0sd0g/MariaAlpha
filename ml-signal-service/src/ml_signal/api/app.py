"""FastAPI sidecar: health, readiness, metrics, model reload."""

from __future__ import annotations

import time
from typing import TYPE_CHECKING, Any

from fastapi import FastAPI
from fastapi.responses import PlainTextResponse
from prometheus_client import generate_latest

from ml_signal.metrics import FEATURE_STALENESS

if TYPE_CHECKING:
    from ml_signal.config import Settings
    from ml_signal.features.engine import FeatureEngine
    from ml_signal.model.signal_model import SignalModel


def create_app(
    feature_engine: FeatureEngine,
    signal_model: SignalModel,
    settings: Settings,
) -> FastAPI:
    """Create and configure the FastAPI application."""
    app = FastAPI(
        title="ML Signal Service",
        version="0.1.0",
        docs_url="/openapi.json",
    )

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "healthy"}

    @app.get("/ready")
    def ready() -> dict[str, Any]:
        symbols_with_features = feature_engine.symbols_with_features()
        model_loaded = signal_model.is_loaded
        is_ready = model_loaded and len(symbols_with_features) > 0
        return {
            "status": "ready" if is_ready else "not_ready",
            "model_loaded": model_loaded,
            "symbols_with_features": symbols_with_features,
        }

    @app.get("/metrics", response_class=PlainTextResponse)
    def metrics() -> bytes:
        # Update staleness gauges before scrape
        now = time.time()
        for symbol, last_update in feature_engine.last_update_times().items():
            FEATURE_STALENESS.labels(symbol=symbol).set(now - last_update)
        return generate_latest()

    @app.post("/v1/models/reload")
    def reload_model(path: str | None = None) -> dict[str, Any]:
        result = signal_model.reload(path)
        return result

    return app
