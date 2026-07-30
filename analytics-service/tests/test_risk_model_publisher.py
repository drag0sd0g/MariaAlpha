"""Risk-model publisher, positions consumer, basket client and the limit-alert path.

These are the seams between the analytics service and the rest of the system, so they are tested
against the *contract* the other side actually parses — the Java ``RiskModelSnapshot`` record and
``BasketOrderRequest`` in particular.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch

import numpy as np
import pytest

from analytics.config import Settings
from analytics.consumer.market_data import MarketDataCache
from analytics.portfolio.basket_client import BasketClient, BasketSubmissionError
from analytics.portfolio.covariance import EstimatorSettings, estimate, from_supplied
from analytics.portfolio.reference import PortfolioReference
from analytics.portfolio.service import CovarianceService
from analytics.portfolio.state import PortfolioState
from analytics.publisher.risk_model import MODEL_KEY, RiskModelPublisher, build_payload
from analytics.risk.engine import RiskEngine
from analytics.risk.stress import load_scenarios

from .conftest import REPO_CONFIG, UNIVERSE


@pytest.fixture
def sample_estimate(reference, estimator_settings: EstimatorSettings):
    return estimate(UNIVERSE, reference, estimator_settings)


class TestBuildPayload:
    def test_carries_every_field_the_java_consumer_requires(self, sample_estimate) -> None:
        payload = build_payload(sample_estimate, "ewma+ledoit_wolf")
        for field in (
            "modelId",
            "generatedAt",
            "estimator",
            "source",
            "observations",
            "tradingDaysPerYear",
            "symbols",
            "annualizedVolatility",
            "correlation",
        ):
            assert field in payload, f"execution-engine's RiskModelSnapshot needs {field}"

    def test_generated_at_uses_a_z_suffix_java_instant_can_parse(self, sample_estimate) -> None:
        payload = build_payload(sample_estimate, "test")
        assert payload["generatedAt"].endswith("Z")
        assert "+00:00" not in payload["generatedAt"]

    def test_correlation_is_square_with_a_unit_diagonal(self, sample_estimate) -> None:
        payload = build_payload(sample_estimate, "test")
        n = len(payload["symbols"])
        correlation = payload["correlation"]
        assert len(correlation) == n
        for i in range(n):
            assert len(correlation[i]) == n
            assert correlation[i][i] == pytest.approx(1.0, abs=1e-6)

    def test_correlation_is_symmetric_within_the_java_tolerance(self, sample_estimate) -> None:
        """The Java side rejects asymmetry beyond 1e-6, and rounding must not trip it."""
        correlation = build_payload(sample_estimate, "test")["correlation"]
        n = len(correlation)
        for i in range(n):
            for j in range(n):
                assert abs(correlation[i][j] - correlation[j][i]) <= 1e-6

    def test_volatilities_are_finite_and_non_negative(self, sample_estimate) -> None:
        vols = build_payload(sample_estimate, "test")["annualizedVolatility"]
        assert all(np.isfinite(v) and v >= 0 for v in vols)

    def test_payload_is_json_serialisable(self, sample_estimate) -> None:
        encoded = json.dumps(build_payload(sample_estimate, "test"))
        assert json.loads(encoded)["symbols"] == list(UNIVERSE)


class TestRiskModelPublisher:
    def _publisher(self) -> tuple[RiskModelPublisher, MagicMock]:
        with patch("analytics.publisher.risk_model.Producer") as producer_cls:
            producer = MagicMock()
            producer_cls.return_value = producer
            publisher = RiskModelPublisher(Settings())
        return publisher, producer

    def test_publishes_under_the_single_compaction_key(self, sample_estimate) -> None:
        publisher, producer = self._publisher()
        publisher.publish(sample_estimate)
        producer.produce.assert_called_once()
        kwargs = producer.produce.call_args.kwargs
        assert kwargs["key"] == MODEL_KEY.encode("utf-8")

    def test_published_value_round_trips_as_json(self, sample_estimate) -> None:
        publisher, producer = self._publisher()
        publisher.publish(sample_estimate)
        value = producer.produce.call_args.kwargs["value"]
        assert json.loads(value.decode("utf-8"))["symbols"] == list(UNIVERSE)

    def test_skips_an_empty_estimate(self) -> None:
        publisher, producer = self._publisher()
        empty = from_supplied((), np.zeros((0, 0)))
        assert publisher.publish(empty) is None
        producer.produce.assert_not_called()

    def test_producer_failure_is_swallowed(self, sample_estimate) -> None:
        publisher, producer = self._publisher()
        producer.produce.side_effect = RuntimeError("broker down")
        assert publisher.publish(sample_estimate) is None

    def test_close_flushes(self, sample_estimate) -> None:
        publisher, producer = self._publisher()
        publisher.close()
        producer.flush.assert_called_once()

    def test_close_swallows_flush_failures(self) -> None:
        publisher, producer = self._publisher()
        producer.flush.side_effect = RuntimeError("nope")
        publisher.close()


class TestPositionsConsumer:
    def _consumer(self, state: PortfolioState):
        from analytics.consumer.positions import PositionsConsumer

        with patch("analytics.consumer.positions.Consumer") as consumer_cls:
            kafka = MagicMock()
            consumer_cls.return_value = kafka
            consumer = PositionsConsumer(Settings(), state)
        return consumer, kafka

    def test_subscribes_from_earliest_so_the_book_survives_a_restart(self) -> None:
        state = PortfolioState(base_nav=0.0)
        with patch("analytics.consumer.positions.Consumer") as consumer_cls:
            from analytics.consumer.positions import PositionsConsumer

            PositionsConsumer(Settings(), state)
        config = consumer_cls.call_args.args[0]
        assert config["auto.offset.reset"] == "earliest"

    def test_applies_a_snapshot_from_the_stream(self) -> None:
        state = PortfolioState(base_nav=0.0)
        consumer, kafka = self._consumer(state)
        payload = json.dumps(
            {"symbol": "AAPL", "netQuantity": 100, "avgEntryPrice": 200.0, "lastMarkPrice": 210.0}
        ).encode()

        message = MagicMock()
        message.error.return_value = None
        message.value.return_value = payload

        def poll(timeout: float) -> Any:
            consumer._running = False
            return message

        kafka.poll.side_effect = poll
        consumer.run()

        assert state.count() == 1
        assert state.snapshot().positions[0].quantity == 100

    def test_stop_closes_the_consumer(self) -> None:
        state = PortfolioState(base_nav=0.0)
        consumer, kafka = self._consumer(state)
        consumer.stop()
        kafka.close.assert_called_once()


class TestBasketClient:
    def test_is_disabled_by_default(self) -> None:
        client = BasketClient(Settings())
        assert client.enabled is False
        with pytest.raises(BasketSubmissionError, match="disabled"):
            client.submit({"name": "x", "legs": [{"symbol": "AAPL"}]})

    def test_refuses_an_empty_basket_even_when_enabled(self) -> None:
        client = BasketClient(Settings(rebalance_submit_enabled=True))
        with pytest.raises(BasketSubmissionError, match="no legs"):
            client.submit({"name": "x", "legs": []})

    def test_posts_to_the_execution_engine_basket_endpoint(self) -> None:
        client = BasketClient(
            Settings(rebalance_submit_enabled=True, execution_engine_url="http://exec:8084")
        )
        response = MagicMock()
        response.status_code = 202
        response.json.return_value = {"basketId": "abc"}

        with patch("analytics.portfolio.basket_client.httpx.post", return_value=response) as post:
            result = client.submit({"name": "x", "legs": [{"symbol": "AAPL", "quantity": 1}]})

        assert post.call_args.args[0] == "http://exec:8084/api/execution/baskets"
        assert result["status"] == 202
        assert result["basket"]["basketId"] == "abc"

    def test_surfaces_a_rejection_with_its_status_code(self) -> None:
        client = BasketClient(Settings(rebalance_submit_enabled=True))
        response = MagicMock()
        response.status_code = 400
        response.text = "quantity must be at least 1"

        with (
            patch("analytics.portfolio.basket_client.httpx.post", return_value=response),
            pytest.raises(BasketSubmissionError) as excinfo,
        ):
            client.submit({"name": "x", "legs": [{"symbol": "AAPL", "quantity": 0}]})
        assert excinfo.value.status_code == 400

    def test_surfaces_a_transport_failure(self) -> None:
        import httpx

        client = BasketClient(Settings(rebalance_submit_enabled=True))
        with (
            patch(
                "analytics.portfolio.basket_client.httpx.post",
                side_effect=httpx.ConnectError("refused"),
            ),
            pytest.raises(BasketSubmissionError, match="unreachable"),
        ):
            client.submit({"name": "x", "legs": [{"symbol": "AAPL", "quantity": 1}]})

    def test_handles_a_non_json_response_body(self) -> None:
        client = BasketClient(Settings(rebalance_submit_enabled=True))
        response = MagicMock()
        response.status_code = 202
        response.json.side_effect = ValueError("not json")
        response.text = "accepted"

        with patch("analytics.portfolio.basket_client.httpx.post", return_value=response):
            result = client.submit({"name": "x", "legs": [{"symbol": "AAPL", "quantity": 1}]})
        assert result["basket"] == {"raw": "accepted"}


class TestRiskEngineLimits:
    def _engine(self, alerts: list[dict[str, object]], **overrides: object) -> RiskEngine:
        settings = Settings(
            portfolio_reference_path=str(REPO_CONFIG / "portfolio.yml"),
            stress_scenarios_path=str(REPO_CONFIG / "stress-scenarios.yml"),
            risk_mc_simulations=500,
            **overrides,
        )
        reference = PortfolioReference.load(Path(settings.portfolio_reference_path))
        cache = MarketDataCache()
        for symbol, price in (("NVDA", 500.0), ("MSFT", 414.0)):
            cache.record(symbol, 1000.0, price, arrival_seconds=1000.0)
        state = PortfolioState(base_nav=1_000_000.0, price_lookup=cache.latest)
        state.apply({"symbol": "NVDA", "netQuantity": 40_000, "avgEntryPrice": 500.0})
        covariance = CovarianceService(settings, cache, reference)
        return RiskEngine(
            settings,
            covariance,
            state,
            reference,
            load_scenarios(Path(settings.stress_scenarios_path)),
            market_cache=cache,
            alert_publisher=alerts.append,
        )

    def test_publishes_a_var_breach_alert(self) -> None:
        alerts: list[dict[str, object]] = []
        engine = self._engine(alerts, risk_var_limit_usd=1_000.0)
        engine.evaluate_limits()
        types = {a["type"] for a in alerts}
        assert "PORTFOLIO_VAR_BREACH" in types

    def test_var_breach_alert_carries_the_diversification_ratio(self) -> None:
        alerts: list[dict[str, object]] = []
        engine = self._engine(alerts, risk_var_limit_usd=1_000.0)
        engine.evaluate_limits()
        breach = next(a for a in alerts if a["type"] == "PORTFOLIO_VAR_BREACH")
        assert "diversificationRatio" in breach
        assert breach["symbol"] == "PORTFOLIO"

    def test_publishes_stress_breach_alerts(self) -> None:
        alerts: list[dict[str, object]] = []
        engine = self._engine(alerts, stress_loss_limit_usd=1_000.0)
        engine.evaluate_limits()
        assert any(a["type"] == "STRESS_LIMIT_BREACH" for a in alerts)

    def test_no_alerts_when_limits_are_generous(self) -> None:
        alerts: list[dict[str, object]] = []
        engine = self._engine(alerts, risk_var_limit_usd=1e12, stress_loss_limit_usd=1e12)
        engine.evaluate_limits()
        assert alerts == []

    def test_empty_book_produces_no_report(self) -> None:
        settings = Settings(portfolio_reference_path=str(REPO_CONFIG / "portfolio.yml"))
        reference = PortfolioReference.load(Path(settings.portfolio_reference_path))
        cache = MarketDataCache()
        state = PortfolioState(base_nav=1_000_000.0)
        engine = RiskEngine(
            settings, CovarianceService(settings, cache, reference), state, reference
        )
        assert engine.evaluate_limits() is None

    def test_evaluate_on_an_empty_book_reports_zero_risk(self) -> None:
        settings = Settings(portfolio_reference_path=str(REPO_CONFIG / "portfolio.yml"))
        reference = PortfolioReference.load(Path(settings.portfolio_reference_path))
        cache = MarketDataCache()
        state = PortfolioState(base_nav=1_000_000.0)
        engine = RiskEngine(
            settings, CovarianceService(settings, cache, reference), state, reference
        )
        report = engine.evaluate()
        assert report.parametric.var_usd == 0.0
        assert report.symbols == ()
        assert "portfolio is empty" in report.parametric.notes


class TestCovarianceService:
    def test_caches_within_the_ttl(self, reference) -> None:
        settings = Settings()
        cache = MarketDataCache()
        service = CovarianceService(settings, cache, reference)
        first = service.current()
        second = service.current()
        assert first is second

    def test_invalidate_forces_a_recompute(self, reference) -> None:
        service = CovarianceService(Settings(), MarketDataCache(), reference)
        first = service.current()
        service.invalidate()
        assert service.current() is not first

    def test_universe_falls_back_to_tracked_symbols(self) -> None:
        cache = MarketDataCache()
        cache.record("AAPL", 0.0, 100.0, arrival_seconds=0.0)
        service = CovarianceService(Settings(), cache, PortfolioReference())
        assert service.universe() == ("AAPL",)

    def test_rejects_an_empty_universe(self) -> None:
        service = CovarianceService(Settings(), MarketDataCache(), PortfolioReference())
        with pytest.raises(ValueError, match="no symbols configured"):
            service.current()
