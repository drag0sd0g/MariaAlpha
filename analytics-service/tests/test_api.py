"""Integration tests for the analytics FastAPI app — drives the REST surface end-to-end.

Stubs the Kafka consumers but uses real ``FlowToxicityDetector``, ``PnlAttributionEngine`` and
``AxeMatcher`` instances so wiring (config → detector → API → JSON) is exercised together.
"""

from __future__ import annotations

from datetime import datetime

import pytest
from fastapi.testclient import TestClient

from analytics.api.app import create_app
from analytics.axes.matcher import AxeMatcher, IncomingLeg
from analytics.config import Settings
from analytics.consumer.market_data import MarketDataCache
from analytics.pnl.attribution import PnlAttributionEngine, TcaInput
from analytics.toxicity.detector import FillRecord, FlowToxicityDetector


@pytest.fixture
def settings() -> Settings:
    return Settings(
        kafka_bootstrap_servers="localhost:9092",
        toxicity_horizons_seconds=(60,),
        toxicity_threshold_bps=5.0,
        toxicity_min_observations=1,
        commission_bps=0.5,
        axe_default_ttl_minutes=60,
        axe_match_min_quantity=1,
    )


@pytest.fixture
def cache() -> MarketDataCache:
    return MarketDataCache()


@pytest.fixture
def matcher() -> AxeMatcher:
    return AxeMatcher(default_ttl_seconds=3600, min_match_quantity=1)


@pytest.fixture
def attribution() -> PnlAttributionEngine:
    return PnlAttributionEngine(commission_bps=0.5)


@pytest.fixture
def toxicity(cache: MarketDataCache) -> FlowToxicityDetector:
    return FlowToxicityDetector(
        horizons_seconds=(60,),
        threshold_bps=5.0,
        min_observations=1,
        price_lookup=cache.price_at,
    )


@pytest.fixture
def client(
    settings: Settings,
    toxicity: FlowToxicityDetector,
    attribution: PnlAttributionEngine,
    matcher: AxeMatcher,
    cache: MarketDataCache,
) -> TestClient:
    class _StubOrdersConsumer:
        def __init__(self, m: AxeMatcher) -> None:
            self._matcher = m
            self._last: dict[str, list[dict[str, object]]] = {}

        def record(self, leg: IncomingLeg) -> list[dict[str, object]]:
            suggestions = self._matcher.match(leg)
            rows = [
                {
                    "axeId": s.axe_id,
                    "clientId": s.client_id,
                    "symbol": s.symbol,
                    "axeSide": s.axe_side,
                    "matchedQuantity": s.matched_quantity,
                    "confidence": s.confidence,
                    "score": s.score,
                }
                for s in suggestions
            ]
            self._last[leg.order_id] = rows
            return rows

        def last_matches(self, order_id: str) -> list[dict[str, object]] | None:
            return self._last.get(order_id)

    stub = _StubOrdersConsumer(matcher)
    app = create_app(settings, toxicity, attribution, matcher, cache, orders_consumer=stub)
    app.state.stub_orders = stub  # type: ignore[attr-defined]
    return TestClient(app)




def test_health_endpoint_returns_healthy(client: TestClient):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "healthy"}


def test_actuator_health_returns_spring_compatible_payload(client: TestClient):
    r = client.get("/actuator/health")
    assert r.status_code == 200
    assert r.json() == {"status": "UP"}


def test_ready_endpoint_reports_counts(client: TestClient):
    r = client.get("/ready")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ready"
    assert "pendingToxicityFills" in body
    assert "activeAxes" in body


def test_metrics_endpoint_returns_prometheus_exposition(client: TestClient):
    r = client.get("/metrics")
    assert r.status_code == 200
    assert "# HELP" in r.text




def test_flow_toxicity_endpoint_returns_empty_until_fills_arrive(client: TestClient):
    r = client.get("/v1/analytics/flow/toxicity")
    assert r.status_code == 200
    body = r.json()
    assert body["rows"] == []
    assert body["thresholdBps"] == 5.0
    assert body["horizonsSeconds"] == [60]


def test_flow_toxicity_endpoint_returns_rows_after_tick(
    client: TestClient,
    toxicity: FlowToxicityDetector,
    cache: MarketDataCache,
):
    cache.record("AAPL", 60.0, 99.0)
    toxicity.on_fill(FillRecord("o1", "VWAP", "AAPL", "BUY", 100.0, 0.0))
    toxicity._clock = lambda: 70.0  # type: ignore[assignment]
    toxicity.tick()
    r = client.get("/v1/analytics/flow/toxicity?strategy=VWAP")
    body = r.json()
    assert len(body["rows"]) == 1
    assert body["rows"][0]["strategy"] == "VWAP"




def _tca(order_id: str = "o1") -> TcaInput:
    return TcaInput(
        order_id=order_id,
        strategy="VWAP",
        symbol="AAPL",
        side="BUY",
        quantity=100.0,
        arrival_price=100.0,
        realized_avg_price=100.10,
        vwap_benchmark_price=100.05,
        spread_cost_bps=5.0,
        commission_total=None,
        computed_at=datetime(2026, 5, 30, 14, 30, 0),
    )


def test_pnl_attribution_daily_endpoint_empty_initially(client: TestClient):
    r = client.get("/v1/analytics/pnl/attribution")
    assert r.status_code == 200
    assert r.json() == {"daily": []}


def test_pnl_attribution_returns_daily_summary_after_attribution(
    client: TestClient, attribution: PnlAttributionEngine
):
    attribution.attribute(_tca("o1"))
    attribution.attribute(_tca("o2"))
    r = client.get("/v1/analytics/pnl/attribution")
    body = r.json()
    assert len(body["daily"]) == 1
    assert body["daily"][0]["orders"] == 2


def test_pnl_attribution_order_endpoint_404_when_unknown(client: TestClient):
    r = client.get("/v1/analytics/pnl/attribution/nope")
    assert r.status_code == 404


def test_pnl_attribution_order_endpoint_returns_breakdown(
    client: TestClient, attribution: PnlAttributionEngine
):
    attribution.attribute(_tca("o1"))
    r = client.get("/v1/analytics/pnl/attribution/o1")
    body = r.json()
    assert body["orderId"] == "o1"
    assert "spreadUsd" in body and "marketUsd" in body


def test_pnl_attribution_by_strategy_endpoint(
    client: TestClient, attribution: PnlAttributionEngine
):
    attribution.attribute(_tca("o1"))
    r = client.get("/v1/analytics/pnl/attribution/by-strategy/VWAP")
    body = r.json()
    assert body["strategy"] == "VWAP"
    assert body["orders"] == 1
    assert "spread" in body["components"]




def test_publish_axe_via_post_creates_axe(client: TestClient):
    r = client.post(
        "/v1/analytics/axes",
        json={
            "axe_id": "a1",
            "client_id": "CLNT_A",
            "symbol": "AAPL",
            "side": "BUY",
            "quantity": 1000,
            "limit_price": 99.5,
            "ttl_seconds": 600,
        },
    )
    assert r.status_code == 201
    body = r.json()
    assert body["axeId"] == "a1"
    assert body["remaining"] == 1000


def test_publish_axe_rejects_invalid_side(client: TestClient):
    r = client.post(
        "/v1/analytics/axes",
        json={
            "axe_id": "a1",
            "client_id": "CLNT_A",
            "symbol": "AAPL",
            "side": "HOLD",
            "quantity": 100,
        },
    )
    assert r.status_code == 422


def test_publish_axe_rejects_non_positive_quantity(client: TestClient):
    r = client.post(
        "/v1/analytics/axes",
        json={
            "axe_id": "a1",
            "client_id": "CLNT_A",
            "symbol": "AAPL",
            "side": "BUY",
            "quantity": 0,
        },
    )
    assert r.status_code == 422


def test_list_axes_returns_published_axes(client: TestClient):
    client.post(
        "/v1/analytics/axes",
        json={
            "axe_id": "a1",
            "client_id": "CLNT_A",
            "symbol": "AAPL",
            "side": "BUY",
            "quantity": 1000,
        },
    )
    r = client.get("/v1/analytics/axes?symbol=AAPL")
    body = r.json()
    assert len(body["axes"]) == 1
    assert body["axes"][0]["axeId"] == "a1"
    assert body["stats"]["activeAxes"] == 1


def test_cancel_axe_removes_it(client: TestClient):
    client.post(
        "/v1/analytics/axes",
        json={
            "axe_id": "a1",
            "client_id": "CLNT_A",
            "symbol": "AAPL",
            "side": "BUY",
            "quantity": 1000,
        },
    )
    r = client.delete("/v1/analytics/axes/a1")
    assert r.status_code == 204
    r2 = client.delete("/v1/analytics/axes/a1")
    assert r2.status_code == 404


def test_axe_matches_endpoint_404_when_no_orders_seen(client: TestClient):
    r = client.get("/v1/analytics/axes/matches/nope")
    assert r.status_code == 404


def test_axe_matches_endpoint_returns_recorded_matches(client: TestClient):
    client.post(
        "/v1/analytics/axes",
        json={
            "axe_id": "a1",
            "client_id": "CLNT_A",
            "symbol": "AAPL",
            "side": "SELL",
            "quantity": 1000,
        },
    )
    stub = client.app.state.stub_orders  # type: ignore[attr-defined]
    stub.record(IncomingLeg("ORDER_1", "AAPL", "BUY", 400))
    r = client.get("/v1/analytics/axes/matches/ORDER_1")
    body = r.json()
    assert body["orderId"] == "ORDER_1"
    assert len(body["matches"]) == 1
    assert body["matches"][0]["axeId"] == "a1"
    assert body["matches"][0]["matchedQuantity"] == 400
