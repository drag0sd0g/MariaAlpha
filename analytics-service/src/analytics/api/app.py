"""FastAPI app — health, metrics, and the three analytics REST surfaces."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from fastapi import FastAPI, HTTPException, Response
from fastapi.responses import PlainTextResponse
from prometheus_client import generate_latest
from pydantic import BaseModel, Field

from analytics.metrics import AXES_ACTIVE

if TYPE_CHECKING:
    from analytics.axes.matcher import AxeMatcher
    from analytics.config import Settings
    from analytics.consumer.market_data import MarketDataCache
    from analytics.consumer.orders_consumer import OrdersConsumer
    from analytics.pnl.attribution import PnlAttributionEngine
    from analytics.toxicity.detector import FlowToxicityDetector


class AxePublishRequest(BaseModel):
    axe_id: str = Field(min_length=1, max_length=64)
    client_id: str = Field(min_length=1, max_length=64)
    symbol: str = Field(min_length=1, max_length=16)
    side: str = Field(pattern="^(BUY|SELL)$")
    quantity: int = Field(gt=0)
    limit_price: float | None = None
    ttl_seconds: int | None = Field(default=None, gt=0)


def create_app(
    settings: Settings,
    toxicity: FlowToxicityDetector,
    attribution: PnlAttributionEngine,
    matcher: AxeMatcher,
    market_cache: MarketDataCache,
    orders_consumer: OrdersConsumer | None = None,
) -> FastAPI:
    app = FastAPI(
        title="MariaAlpha Analytics Service",
        version="0.1.0",
        description=("MariaAlpha analytics: flow toxicity, PnL attribution, and axe matching."),
        docs_url="/openapi.json",
    )


    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "healthy"}

    @app.get("/actuator/health")
    def actuator_health() -> dict[str, str]:
        return {"status": "UP"}

    @app.get("/ready")
    def ready() -> dict[str, Any]:
        return {
            "status": "ready",
            "pendingToxicityFills": toxicity.pending_count(),
            "marketDataSymbols": len(market_cache._history),
            "activeAxes": matcher.stats()["activeAxes"],
        }

    @app.get("/metrics", response_class=PlainTextResponse)
    def metrics() -> bytes:
        for row in matcher.snapshot():
            AXES_ACTIVE.labels(symbol=str(row["symbol"]), side=str(row["side"])).set(1)
        return generate_latest()


    @app.get("/v1/analytics/flow/toxicity")
    def get_flow_toxicity(strategy: str | None = None) -> dict[str, Any]:
        return {
            "rows": toxicity.snapshot(strategy=strategy),
            "thresholdBps": settings.toxicity_threshold_bps,
            "horizonsSeconds": list(settings.toxicity_horizons_seconds),
        }


    @app.get("/v1/analytics/pnl/attribution")
    def get_pnl_attribution(strategy: str | None = None) -> dict[str, Any]:
        return {
            "daily": attribution.daily_summary(strategy=strategy),
        }

    @app.get("/v1/analytics/pnl/attribution/{order_id}")
    def get_order_pnl_attribution(order_id: str) -> dict[str, Any]:
        row = attribution.order_breakdown(order_id)
        if row is None:
            raise HTTPException(status_code=404, detail=f"no TCA seen for order {order_id}")
        return row

    @app.get("/v1/analytics/pnl/attribution/by-strategy/{strategy}")
    def get_strategy_distribution(strategy: str) -> dict[str, Any]:
        return attribution.strategy_distribution(strategy)


    @app.post("/v1/analytics/axes", status_code=201)
    def publish_axe(req: AxePublishRequest) -> dict[str, Any]:
        axe = matcher.publish(
            axe_id=req.axe_id,
            client_id=req.client_id,
            symbol=req.symbol,
            side=req.side,
            quantity=req.quantity,
            limit_price=req.limit_price,
            ttl_seconds=req.ttl_seconds,
        )
        return {
            "axeId": axe.axe_id,
            "clientId": axe.client_id,
            "symbol": axe.symbol,
            "side": axe.side,
            "quantity": axe.quantity,
            "remaining": axe.remaining,
            "limitPrice": axe.limit_price,
            "expiresAt": axe.expires_at,
            "refreshCount": axe.refresh_count,
        }

    @app.delete("/v1/analytics/axes/{axe_id}")
    def cancel_axe(axe_id: str) -> Response:
        if not matcher.cancel(axe_id):
            raise HTTPException(status_code=404, detail=f"axe {axe_id} not found")
        return Response(status_code=204)

    @app.get("/v1/analytics/axes")
    def list_axes(symbol: str | None = None, side: str | None = None) -> dict[str, Any]:
        return {
            "axes": matcher.snapshot(symbol=symbol, side=side),
            "stats": matcher.stats(),
        }

    @app.get("/v1/analytics/axes/matches/{order_id}")
    def axe_matches_for_order(order_id: str) -> dict[str, Any]:
        if orders_consumer is None:
            raise HTTPException(status_code=503, detail="orders consumer not running")
        matches = orders_consumer.last_matches(order_id)
        if matches is None:
            raise HTTPException(
                status_code=404, detail=f"no axe matches recorded for order {order_id}"
            )
        return {"orderId": order_id, "matches": matches}

    return app
