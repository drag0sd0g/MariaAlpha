"""End-to-end tests for the portfolio and risk REST surface.

Uses real components (covariance service, risk engine, optimisers) behind a FastAPI TestClient —
only Kafka is absent — so wiring, validation and JSON shape are exercised together.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from analytics.api.app import create_app
from analytics.axes.matcher import AxeMatcher
from analytics.config import Settings
from analytics.consumer.market_data import MarketDataCache
from analytics.pnl.attribution import PnlAttributionEngine
from analytics.portfolio.reference import PortfolioReference
from analytics.portfolio.service import CovarianceService
from analytics.portfolio.state import PortfolioState
from analytics.risk.engine import RiskEngine
from analytics.risk.stress import load_scenarios
from analytics.toxicity.detector import FlowToxicityDetector

from .conftest import REPO_CONFIG

PRICES = {
    "AAPL": 200.0,
    "MSFT": 414.0,
    "GOOGL": 156.0,
    "AMZN": 185.0,
    "TSLA": 245.0,
    "NVDA": 500.0,
}


@pytest.fixture
def settings() -> Settings:
    return Settings(
        portfolio_reference_path=str(REPO_CONFIG / "portfolio.yml"),
        stress_scenarios_path=str(REPO_CONFIG / "stress-scenarios.yml"),
        portfolio_base_nav=1_000_000.0,
        risk_mc_simulations=2_000,
        rebalance_submit_enabled=False,
    )


@pytest.fixture
def market_cache() -> MarketDataCache:
    cache = MarketDataCache()
    for symbol, price in PRICES.items():
        cache.record(symbol, 1000.0, price, arrival_seconds=1000.0)
    return cache


@pytest.fixture
def portfolio_state(market_cache: MarketDataCache) -> PortfolioState:
    state = PortfolioState(base_nav=1_000_000.0, price_lookup=market_cache.latest)
    state.apply({"symbol": "NVDA", "netQuantity": 2000, "avgEntryPrice": 500.0})
    state.apply({"symbol": "MSFT", "netQuantity": -2415, "avgEntryPrice": 414.0})
    return state


@pytest.fixture
def client(
    settings: Settings, market_cache: MarketDataCache, portfolio_state: PortfolioState
) -> TestClient:
    reference = PortfolioReference.load(Path(settings.portfolio_reference_path))
    scenarios = load_scenarios(Path(settings.stress_scenarios_path))
    covariance = CovarianceService(settings, market_cache, reference)
    engine = RiskEngine(
        settings, covariance, portfolio_state, reference, scenarios, market_cache=market_cache
    )
    app = create_app(
        settings=settings,
        toxicity=FlowToxicityDetector(
            horizons_seconds=(60,),
            threshold_bps=5.0,
            min_observations=1,
            price_lookup=market_cache.price_at,
        ),
        attribution=PnlAttributionEngine(),
        matcher=AxeMatcher(default_ttl_seconds=3600, min_match_quantity=1),
        market_cache=market_cache,
        reference=reference,
        portfolio_state=portfolio_state,
        covariance_service=covariance,
        risk_engine=engine,
    )
    return TestClient(app)


@pytest.fixture
def bare_client(settings: Settings) -> TestClient:
    """App built the pre-4.6.1 way — no portfolio components wired."""
    cache = MarketDataCache()
    app = create_app(
        settings=settings,
        toxicity=FlowToxicityDetector(
            horizons_seconds=(60,),
            threshold_bps=5.0,
            min_observations=1,
            price_lookup=cache.price_at,
        ),
        attribution=PnlAttributionEngine(),
        matcher=AxeMatcher(default_ttl_seconds=3600, min_match_quantity=1),
        market_cache=cache,
    )
    return TestClient(app)


class TestPortfolioState:
    def test_returns_the_live_book(self, client: TestClient) -> None:
        body = client.get("/v1/analytics/portfolio/state").json()
        assert body["positionCount"] == 2
        assert {p["symbol"] for p in body["positions"]} == {"MSFT", "NVDA"}
        assert body["navSource"] == "config-cash + marked-positions"

    def test_reports_signed_notionals(self, client: TestClient) -> None:
        body = client.get("/v1/analytics/portfolio/state").json()
        by_symbol = {p["symbol"]: p for p in body["positions"]}
        assert by_symbol["NVDA"]["notionalUsd"] > 0
        assert by_symbol["MSFT"]["notionalUsd"] < 0


class TestCovarianceEndpoints:
    def test_get_returns_a_square_unit_diagonal_correlation(self, client: TestClient) -> None:
        body = client.get("/v1/analytics/portfolio/covariance").json()
        n = len(body["symbols"])
        assert len(body["correlation"]) == n
        assert all(len(row) == n for row in body["correlation"])
        assert all(body["correlation"][i][i] == pytest.approx(1.0, abs=1e-9) for i in range(n))

    def test_get_accepts_a_symbol_subset(self, client: TestClient) -> None:
        body = client.get("/v1/analytics/portfolio/covariance?symbols=NVDA,MSFT").json()
        assert body["symbols"] == ["NVDA", "MSFT"]

    def test_diagnostics_declare_provenance(self, client: TestClient) -> None:
        body = client.get("/v1/analytics/portfolio/covariance").json()
        assert body["diagnostics"]["source"] in {"prior", "sample+prior"}
        assert "observations" in body["diagnostics"]

    def test_post_accepts_a_supplied_matrix(self, client: TestClient) -> None:
        body = client.post(
            "/v1/analytics/portfolio/covariance",
            json={"symbols": ["A", "B"], "covariance": [[0.04, 0.006], [0.006, 0.09]]},
        ).json()
        assert body["diagnostics"]["source"] == "supplied"
        assert body["annualizedVolatility"] == pytest.approx([0.2, 0.3], abs=1e-9)

    def test_post_rejects_a_mismatched_matrix(self, client: TestClient) -> None:
        response = client.post(
            "/v1/analytics/portfolio/covariance",
            json={"symbols": ["A", "B"], "covariance": [[1.0]]},
        )
        assert response.status_code == 400
        assert "2x2" in response.json()["detail"]


class TestOptimize:
    @pytest.mark.parametrize(
        "objective",
        [
            "MIN_VARIANCE",
            "MEAN_VARIANCE",
            "MAX_SHARPE",
            "RISK_PARITY",
            "EQUAL_WEIGHT",
            "INVERSE_VOL",
        ],
    )
    def test_every_objective_returns_weights_that_sum_to_one(
        self, client: TestClient, objective: str
    ) -> None:
        body = client.post(
            "/v1/analytics/portfolio/optimize",
            json={"objective": objective, "constraints": {"max_weight": 0.35}},
        ).json()
        assert sum(body["weights"].values()) == pytest.approx(1.0, abs=1e-6)

    def test_risk_parity_equalises_contributions(self, client: TestClient) -> None:
        body = client.post(
            "/v1/analytics/portfolio/optimize",
            json={"objective": "RISK_PARITY", "constraints": {"max_weight": 1.0}},
        ).json()
        contributions = list(body["riskContributions"].values())
        assert max(contributions) - min(contributions) < 1e-6

    def test_defaults_to_the_black_litterman_equilibrium(self, client: TestClient) -> None:
        body = client.post(
            "/v1/analytics/portfolio/optimize", json={"objective": "MEAN_VARIANCE"}
        ).json()
        assert body["expectedReturnSource"] == "black-litterman-equilibrium"

    def test_accepts_explicit_expected_returns(self, client: TestClient) -> None:
        body = client.post(
            "/v1/analytics/portfolio/optimize",
            json={
                "objective": "MEAN_VARIANCE",
                "symbols": ["NVDA", "MSFT"],
                "expected_returns": {"NVDA": 0.20, "MSFT": 0.05},
                "constraints": {"max_weight": 1.0},
            },
        ).json()
        assert body["expectedReturnSource"] == "supplied"
        assert sum(body["weights"].values()) == pytest.approx(1.0, abs=1e-6)

    def test_raising_a_symbols_expected_return_raises_its_weight(self, client: TestClient) -> None:
        """Monotonicity is the honest invariant here.

        A higher *level* of expected return does not have to mean a higher weight: NVDA's
        volatility is twice MSFT's, so its variance is four times as large, and at the default
        risk aversion a 15pp return edge does not overcome that penalty. What must hold is that
        pushing one asset's expected return up moves its weight up.
        """

        def nvda_weight(expected: float) -> float:
            body = client.post(
                "/v1/analytics/portfolio/optimize",
                json={
                    "objective": "MEAN_VARIANCE",
                    "symbols": ["NVDA", "MSFT"],
                    "expected_returns": {"NVDA": expected, "MSFT": 0.05},
                    "constraints": {"max_weight": 1.0},
                },
            ).json()
            return float(body["weights"]["NVDA"])

        weights = [nvda_weight(mu) for mu in (0.05, 0.20, 0.60, 1.50)]
        assert weights == sorted(weights)
        assert weights[-1] > weights[0]

    def test_rejects_expected_returns_for_an_unknown_symbol(self, client: TestClient) -> None:
        response = client.post(
            "/v1/analytics/portfolio/optimize",
            json={"symbols": ["NVDA"], "expected_returns": {"ZZZZ": 0.1}},
        )
        assert response.status_code == 400
        assert "unknown symbols" in response.json()["detail"]

    def test_rejects_an_unreachable_max_weight(self, client: TestClient) -> None:
        response = client.post(
            "/v1/analytics/portfolio/optimize", json={"constraints": {"max_weight": 0.05}}
        )
        assert response.status_code == 400
        assert "fully invested" in response.json()["detail"]

    def test_rejects_an_unknown_objective_with_422(self, client: TestClient) -> None:
        response = client.post(
            "/v1/analytics/portfolio/optimize", json={"objective": "GO_LONG_EVERYTHING"}
        )
        assert response.status_code == 422

    def test_universe_spans_the_configured_book_not_just_holdings(self, client: TestClient) -> None:
        """An optimiser limited to current holdings could never suggest buying anything new."""
        body = client.post(
            "/v1/analytics/portfolio/optimize", json={"objective": "EQUAL_WEIGHT"}
        ).json()
        assert set(body["weights"]) >= {"AAPL", "GOOGL", "AMZN", "TSLA"}


class TestEfficientFrontier:
    def test_returns_the_requested_points_in_volatility_order(self, client: TestClient) -> None:
        body = client.post(
            "/v1/analytics/portfolio/efficient-frontier",
            json={"points": 8, "constraints": {"max_weight": 1.0}},
        ).json()
        vols = [p["volatility"] for p in body["points"]]
        assert len(vols) == 8
        assert vols == sorted(vols)

    def test_rejects_too_few_points(self, client: TestClient) -> None:
        assert (
            client.post(
                "/v1/analytics/portfolio/efficient-frontier", json={"points": 1}
            ).status_code
            == 422
        )


class TestBlackLitterman:
    def test_no_views_returns_the_equilibrium(self, client: TestClient) -> None:
        body = client.post("/v1/analytics/portfolio/black-litterman", json={"views": []}).json()
        assert body["posteriorReturns"] == body["equilibriumReturns"]

    def test_a_view_shifts_the_posterior(self, client: TestClient) -> None:
        body = client.post(
            "/v1/analytics/portfolio/black-litterman",
            json={
                "views": [
                    {
                        "name": "bull",
                        "pick": {"NVDA": 1.0},
                        "expected_return": 0.60,
                        "confidence": 2.0,
                    }
                ]
            },
        ).json()
        assert body["viewImpact"]["NVDA"] > 0
        assert body["omegaDiagonal"]["bull"] > 0

    def test_rejects_a_view_on_an_unknown_symbol(self, client: TestClient) -> None:
        response = client.post(
            "/v1/analytics/portfolio/black-litterman",
            json={"views": [{"name": "x", "pick": {"ZZZZ": 1.0}, "expected_return": 0.1}]},
        )
        assert response.status_code == 400
        assert "unknown symbol" in response.json()["detail"]

    def test_rejects_market_weights_for_unknown_symbols(self, client: TestClient) -> None:
        response = client.post(
            "/v1/analytics/portfolio/black-litterman",
            json={"views": [], "market_weights": {"ZZZZ": 1.0}},
        )
        assert response.status_code == 400

    def test_rejects_non_positive_market_weights(self, client: TestClient) -> None:
        response = client.post(
            "/v1/analytics/portfolio/black-litterman",
            json={"views": [], "symbols": ["NVDA"], "market_weights": {"NVDA": 0.0}},
        )
        assert response.status_code == 400
        assert "positive" in response.json()["detail"]

    def test_rejects_non_positive_confidence_with_422(self, client: TestClient) -> None:
        response = client.post(
            "/v1/analytics/portfolio/black-litterman",
            json={
                "views": [
                    {"name": "x", "pick": {"NVDA": 1.0}, "expected_return": 0.1, "confidence": 0.0}
                ]
            },
        )
        assert response.status_code == 422


class TestFactors:
    def test_get_returns_exposures_and_pca(self, client: TestClient) -> None:
        body = client.get("/v1/analytics/portfolio/factors").json()
        assert "MARKET" in body["exposures"]
        assert body["pca"]["varianceExplained"]
        assert sum(body["pca"]["varianceExplained"]) == pytest.approx(1.0, abs=1e-6)

    def test_percentages_sum_to_one(self, client: TestClient) -> None:
        body = client.get("/v1/analytics/portfolio/factors").json()
        assert body["systematicVariancePct"] + body["idiosyncraticVariancePct"] == pytest.approx(
            1.0, abs=1e-6
        )

    def test_accepts_explicit_weights(self, client: TestClient) -> None:
        body = client.post(
            "/v1/analytics/portfolio/factors",
            json={"symbols": ["NVDA", "MSFT"], "weights": {"NVDA": 0.7, "MSFT": 0.3}},
        ).json()
        assert body["weights"] == {"NVDA": 0.7, "MSFT": 0.3}

    def test_rejects_weights_for_unknown_symbols(self, client: TestClient) -> None:
        response = client.post(
            "/v1/analytics/portfolio/factors",
            json={"symbols": ["NVDA"], "weights": {"ZZZZ": 1.0}},
        )
        assert response.status_code == 400


class TestRebalance:
    def test_produces_a_valid_basket_payload(self, client: TestClient) -> None:
        body = client.post(
            "/v1/analytics/portfolio/rebalance",
            json={"objective": "RISK_PARITY", "constraints": {"max_weight": 0.35}},
        ).json()
        basket = body["basketOrderRequest"]
        assert basket["name"]
        assert basket["legs"]
        for leg in basket["legs"]:
            assert leg["side"] in ("BUY", "SELL")
            assert leg["quantity"] >= 1
            assert leg["orderType"] == "MARKET"

    def test_reports_turnover_and_cost(self, client: TestClient) -> None:
        body = client.post(
            "/v1/analytics/portfolio/rebalance",
            json={"objective": "RISK_PARITY", "constraints": {"max_weight": 0.35}},
        ).json()
        assert body["turnoverPct"] > 0
        assert body["estimatedCostUsd"] > 0
        assert body["summary"]["legCount"] == len(body["legs"])

    def test_submit_is_disabled_by_default(self, client: TestClient) -> None:
        body = client.post("/v1/analytics/portfolio/rebalance", json={}).json()
        assert body["submitEnabled"] is False

    def test_accepts_explicit_target_weights(self, client: TestClient) -> None:
        body = client.post(
            "/v1/analytics/portfolio/rebalance",
            json={
                "symbols": ["NVDA", "MSFT"],
                "positions": [
                    {"symbol": "NVDA", "quantity": 2000, "price": 500.0},
                    {"symbol": "MSFT", "quantity": 1000, "price": 400.0},
                ],
                "target_weights": {"NVDA": 0.5, "MSFT": 0.5},
            },
        ).json()
        assert body["message"] == "target weights supplied"

    def test_rejects_target_weights_for_unknown_symbols(self, client: TestClient) -> None:
        response = client.post(
            "/v1/analytics/portfolio/rebalance",
            json={"symbols": ["NVDA"], "target_weights": {"ZZZZ": 1.0}},
        )
        assert response.status_code == 400

    def test_rejects_a_symbol_with_no_price(self, client: TestClient) -> None:
        response = client.post("/v1/analytics/portfolio/rebalance", json={"symbols": ["NOPRICE"]})
        assert response.status_code == 400
        assert "no price available" in response.json()["detail"]

    def test_submit_endpoint_is_503_when_disabled(self, client: TestClient) -> None:
        response = client.post("/v1/analytics/portfolio/rebalance/submit", json={})
        assert response.status_code == 503
        assert "ANALYTICS_REBALANCE_SUBMIT_ENABLED" in response.json()["detail"]


class TestRisk:
    def test_var_reports_all_three_methods(self, client: TestClient) -> None:
        body = client.get("/v1/analytics/risk/var").json()
        assert body["parametric"]["varUsd"] > 0
        assert body["historical"] is not None
        assert body["monteCarlo"]["simulations"] == 2_000

    def test_covariance_var_is_below_the_sum_of_absolutes(self, client: TestClient) -> None:
        body = client.get("/v1/analytics/risk/var").json()
        assert body["parametric"]["varUsd"] < body["sumOfAbsolutesVarUsd"]
        assert body["diversificationRatio"] > 1.0

    def test_post_var_accepts_an_explicit_book(self, client: TestClient) -> None:
        body = client.post(
            "/v1/analytics/risk/var",
            json={
                "positions": [
                    {"symbol": "NVDA", "quantity": 2000, "price": 500.0},
                    {"symbol": "MSFT", "quantity": -2415, "price": 414.0},
                ],
                "confidence": 0.99,
                "horizon_days": 1.0,
            },
        ).json()
        assert body["confidence"] == 0.99
        assert body["parametric"]["varUsd"] > 0

    def test_post_var_rejects_confidence_outside_the_allowed_band(self, client: TestClient) -> None:
        assert client.post("/v1/analytics/risk/var", json={"confidence": 1.5}).status_code == 422
        assert client.post("/v1/analytics/risk/var", json={"confidence": 0.1}).status_code == 422

    def test_components_sum_to_portfolio_var(self, client: TestClient) -> None:
        body = client.get("/v1/analytics/risk/components").json()
        total = sum(row["componentVarUsd"] for row in body["rows"])
        assert total == pytest.approx(body["varUsd"], rel=1e-3)

    def test_the_hedge_shows_a_negative_component(self, client: TestClient) -> None:
        body = client.get("/v1/analytics/risk/components").json()
        by_symbol = {row["symbol"]: row for row in body["rows"]}
        assert by_symbol["MSFT"]["componentVarUsd"] < 0

    def test_stress_returns_every_configured_scenario(self, client: TestClient) -> None:
        body = client.get("/v1/analytics/risk/stress").json()
        names = {s["name"] for s in body["scenarios"]}
        assert {"MARKET_DOWN_10", "TECH_SELLOFF", "COVID_20200316"} <= names

    def test_stress_can_be_filtered_by_name(self, client: TestClient) -> None:
        body = client.post(
            "/v1/analytics/risk/stress", json={"scenario_names": ["MARKET_DOWN_10"]}
        ).json()
        assert [s["name"] for s in body["scenarios"]] == ["MARKET_DOWN_10"]

    def test_unknown_scenario_name_is_404(self, client: TestClient) -> None:
        response = client.post(
            "/v1/analytics/risk/stress", json={"scenario_names": ["NOT_A_SCENARIO"]}
        )
        assert response.status_code == 404

    def test_report_renders_html(self, client: TestClient) -> None:
        response = client.get("/v1/analytics/risk/report")
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/html")
        assert "Component VaR" in response.text
        assert "Diversification credit" in response.text


class TestUnwiredComponents:
    """The 4.6.1 components are optional, so the pre-existing app shape still boots."""

    @pytest.mark.parametrize(
        "path",
        [
            "/v1/analytics/portfolio/state",
            "/v1/analytics/portfolio/covariance",
            "/v1/analytics/risk/var",
            "/v1/analytics/risk/components",
            "/v1/analytics/risk/report",
        ],
    )
    def test_get_endpoints_return_503(self, bare_client: TestClient, path: str) -> None:
        assert bare_client.get(path).status_code == 503

    @pytest.mark.parametrize(
        "path",
        [
            "/v1/analytics/portfolio/optimize",
            "/v1/analytics/portfolio/rebalance",
            "/v1/analytics/risk/var",
        ],
    )
    def test_post_endpoints_return_503(self, bare_client: TestClient, path: str) -> None:
        assert bare_client.post(path, json={}).status_code == 503

    def test_pre_existing_endpoints_are_unaffected(self, bare_client: TestClient) -> None:
        assert bare_client.get("/health").json() == {"status": "healthy"}
        assert bare_client.get("/v1/analytics/flow/toxicity").status_code == 200
        assert bare_client.get("/v1/analytics/pnl/attribution").status_code == 200

    def test_ready_reports_the_new_components(self, client: TestClient) -> None:
        body: dict[str, Any] = client.get("/ready").json()
        assert body["portfolioPositions"] == 2
        assert body["riskEngineWired"] is True
