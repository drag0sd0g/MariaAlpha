"""FastAPI app — health, metrics, and the three analytics REST surfaces."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from fastapi import FastAPI, HTTPException, Response
from fastapi.responses import PlainTextResponse
from prometheus_client import generate_latest
from pydantic import BaseModel, Field

from analytics.api.portfolio_routes import build_portfolio_router
from analytics.metrics import AXES_ACTIVE
from analytics.portfolio.reference import PortfolioReference

if TYPE_CHECKING:
    from analytics.axes.matcher import AxeMatcher
    from analytics.config import Settings
    from analytics.consumer.market_data import MarketDataCache
    from analytics.consumer.orders_consumer import OrdersConsumer
    from analytics.pnl.attribution import PnlAttributionEngine
    from analytics.portfolio.basket_client import BasketClient
    from analytics.portfolio.service import CovarianceService
    from analytics.portfolio.state import PortfolioState
    from analytics.risk.engine import RiskEngine
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
    reference: PortfolioReference | None = None,
    portfolio_state: PortfolioState | None = None,
    covariance_service: CovarianceService | None = None,
    risk_engine: RiskEngine | None = None,
    basket_client: BasketClient | None = None,
) -> FastAPI:
    """Build the FastAPI app.

    The roadmap-4.6.1 components default to ``None`` so existing callers (and the pre-4.6.1
    tests) keep working unchanged; the portfolio and risk routes are always mounted but answer
    ``503`` when their dependency is absent, mirroring the convention already used by
    ``/v1/analytics/axes/matches/{order_id}`` when the orders consumer is not running.
    """
    app = FastAPI(
        title="MariaAlpha Analytics Service",
        version="0.2.0",
        description=(
            "MariaAlpha analytics: flow toxicity, PnL attribution, axe matching, portfolio "
            "construction and the firm-wide risk engine."
        ),
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
            "portfolioPositions": portfolio_state.count() if portfolio_state else 0,
            "riskEngineWired": risk_engine is not None,
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

    app.include_router(
        build_portfolio_router(
            settings=settings,
            reference=reference if reference is not None else PortfolioReference(),
            portfolio_state=portfolio_state,
            covariance_service=covariance_service,
            risk_engine=risk_engine,
            market_cache=market_cache,
            basket_client=basket_client,
        )
    )
    return app
