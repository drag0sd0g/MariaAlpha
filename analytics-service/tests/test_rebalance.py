"""Rebalancer tests — cost model, the buy/sell split, no-trade band, lot rounding, basket shape."""

from __future__ import annotations

import numpy as np
import pytest

from analytics.portfolio.optimizers import Constraints, Objective, optimize_portfolio
from analytics.portfolio.rebalance import (
    CostModel,
    RebalanceSettings,
    TradeLeg,
    _costs,
    build_plan,
    solve,
    summarize_cost,
    to_basket_request,
)

from .conftest import UNIVERSE

NAV = 1_000_000.0
PRICES = np.array([200.0, 415.0, 156.0, 185.0, 245.0, 430.0])
MU = np.array([0.08, 0.07, 0.075, 0.09, 0.11, 0.13])
# Already inside a 0.35 box, so the box never forces a trade the cost model would refuse.
FEASIBLE_W0 = np.array([0.30, 0.15, 0.15, 0.15, 0.10, 0.15])


def zero_cost() -> CostModel:
    return CostModel(
        half_spread_bps={}, commission_bps=0.0, impact_eta=0.0, default_half_spread_bps=0.0
    )


def prohibitive_cost() -> CostModel:
    return CostModel(
        half_spread_bps={},
        commission_bps=100_000.0,
        impact_eta=0.0,
        default_half_spread_bps=100_000.0,
    )


class TestCostModel:
    def test_from_reference_picks_up_config(self, reference) -> None:
        model = CostModel.from_reference(reference)
        assert model.commission_bps == pytest.approx(0.5)
        assert model.adv("NVDA") == pytest.approx(250e6)

    def test_linear_rate_combines_half_spread_and_commission(self, reference) -> None:
        model = CostModel.from_reference(reference)
        assert model.linear_rate("AAPL") == pytest.approx((1.0 + 0.5) / 10_000.0, rel=1e-12)

    def test_unknown_symbol_uses_the_defaults(self) -> None:
        model = CostModel(default_half_spread_bps=3.0, commission_bps=1.0)
        assert model.linear_rate("ZZZZ") == pytest.approx(4.0 / 10_000.0)
        assert model.adv("ZZZZ") == 1_000_000.0

    def test_linear_cost_is_proportional_to_notional(self) -> None:
        model = CostModel(commission_bps=0.0, default_half_spread_bps=10.0, impact_eta=0.0)
        linear, _ = _costs(
            np.array([1e5, 2e5]),
            ["X", "X"],
            np.array([100.0, 100.0]),
            np.array([0.02, 0.02]),
            model,
        )
        assert linear[1] == pytest.approx(2.0 * linear[0], rel=1e-12)

    def test_impact_scales_as_the_three_halves_power(self) -> None:
        model = CostModel(
            commission_bps=0.0, default_half_spread_bps=0.0, impact_eta=1.0, adv_shares={"X": 1e6}
        )
        _, small = _costs(np.array([1e5]), ["X"], np.array([100.0]), np.array([0.02]), model)
        _, large = _costs(np.array([2e5]), ["X"], np.array([100.0]), np.array([0.02]), model)
        assert large[0] / small[0] == pytest.approx(2.0 * np.sqrt(2.0), rel=1e-9)

    def test_zero_trade_costs_nothing(self) -> None:
        linear, impact = _costs(
            np.array([0.0]), ["X"], np.array([100.0]), np.array([0.02]), CostModel()
        )
        assert linear[0] == 0.0
        assert impact[0] == 0.0


class TestSolve:
    def test_zero_cost_reproduces_the_pure_optimum(self, reference) -> None:
        cov = reference.prior_covariance(UNIVERSE)
        constraints = Constraints(max_weight=0.35)
        free = optimize_portfolio(
            Objective.MEAN_VARIANCE,
            UNIVERSE,
            cov,
            mu=MU,
            constraints=constraints,
            risk_aversion=3.0,
        ).weights
        target, converged, _ = solve(
            UNIVERSE, FEASIBLE_W0, MU, cov, NAV, PRICES, zero_cost(), constraints, 3.0
        )
        assert converged
        assert np.allclose(target, free, atol=1e-5)

    def test_prohibitive_cost_leaves_a_feasible_book_untouched(self, reference) -> None:
        cov = reference.prior_covariance(UNIVERSE)
        target, converged, _ = solve(
            UNIVERSE,
            FEASIBLE_W0,
            MU,
            cov,
            NAV,
            PRICES,
            prohibitive_cost(),
            Constraints(max_weight=0.35),
            3.0,
        )
        assert converged
        assert np.allclose(target, FEASIBLE_W0, atol=1e-9)

    def test_binding_box_forces_a_trade_even_at_prohibitive_cost(self, reference) -> None:
        """A position above the cap must be traded down whatever the cost — the box wins."""
        cov = reference.prior_covariance(UNIVERSE)
        infeasible = np.array([0.50, 0.10, 0.10, 0.10, 0.10, 0.10])
        target, _, _ = solve(
            UNIVERSE,
            infeasible,
            MU,
            cov,
            NAV,
            PRICES,
            prohibitive_cost(),
            Constraints(max_weight=0.35),
            3.0,
        )
        assert target[0] <= 0.35 + 1e-6

    def test_target_weights_sum_to_one(self, reference) -> None:
        cov = reference.prior_covariance(UNIVERSE)
        target, _, _ = solve(
            UNIVERSE,
            FEASIBLE_W0,
            MU,
            cov,
            NAV,
            PRICES,
            CostModel.from_reference(reference),
            Constraints(max_weight=0.35),
            3.0,
        )
        assert target.sum() == pytest.approx(1.0, abs=1e-9)


class TestBuildPlan:
    def _plan(self, reference, target, settings=None, w0=None):
        cov = reference.prior_covariance(UNIVERSE)
        current = FEASIBLE_W0 if w0 is None else w0
        return build_plan(
            UNIVERSE,
            current,
            target,
            target,
            NAV,
            PRICES,
            cov,
            MU,
            CostModel.from_reference(reference),
            settings or RebalanceSettings(),
            risk_aversion=3.0,
        )

    def test_no_trade_band_suppresses_a_tiny_reallocation(self, reference) -> None:
        target = FEASIBLE_W0.copy()
        target[0] += 0.001  # 10 bps — below the 25 bps default band
        target[1] -= 0.001
        plan = self._plan(reference, target)
        assert plan.legs == ()
        assert {leg.symbol for leg in plan.suppressed_legs} >= {"AAPL", "MSFT"}

    def test_disabling_the_band_lets_the_small_trades_through(self, reference) -> None:
        target = FEASIBLE_W0.copy()
        target[0] += 0.001
        target[1] -= 0.001
        plan = self._plan(
            reference,
            target,
            RebalanceSettings(min_trade_notional=0.0, no_trade_band_bps=0.0),
        )
        assert {leg.symbol for leg in plan.legs} == {"AAPL", "MSFT"}

    def test_minimum_notional_suppresses_small_dollar_trades(self, reference) -> None:
        target = FEASIBLE_W0.copy()
        target[0] += 0.05
        target[1] -= 0.05
        plan = self._plan(
            reference,
            target,
            RebalanceSettings(min_trade_notional=1e9, no_trade_band_bps=0.0),
        )
        assert plan.legs == ()

    def test_lot_rounding_produces_multiples_of_the_lot_size(self, reference) -> None:
        target = np.array([0.20, 0.20, 0.15, 0.15, 0.15, 0.15])
        plan = self._plan(
            reference,
            target,
            RebalanceSettings(lot_size=100, min_trade_notional=0.0, no_trade_band_bps=0.0),
        )
        assert plan.legs
        assert all(leg.delta_shares % 100 == 0 for leg in plan.legs)

    def test_sides_follow_the_direction_of_the_trade(self, reference) -> None:
        target = FEASIBLE_W0.copy()
        target[0] -= 0.10
        target[5] += 0.10
        plan = self._plan(reference, target)
        by_symbol = {leg.symbol: leg for leg in plan.legs}
        assert by_symbol["AAPL"].side == "SELL"
        assert by_symbol["NVDA"].side == "BUY"

    def test_turnover_is_half_the_summed_absolute_weight_change(self, reference) -> None:
        target = FEASIBLE_W0.copy()
        target[0] -= 0.10
        target[5] += 0.10
        plan = self._plan(reference, target)
        expected = 0.5 * float(np.abs(plan.target_weights - plan.current_weights).sum())
        assert plan.turnover_pct == pytest.approx(expected, rel=1e-9)

    def test_estimated_cost_is_the_sum_of_the_leg_costs(self, reference) -> None:
        target = FEASIBLE_W0.copy()
        target[0] -= 0.10
        target[5] += 0.10
        plan = self._plan(reference, target)
        assert plan.estimated_cost_usd == pytest.approx(
            sum(leg.total_cost_usd for leg in plan.legs), rel=1e-12
        )

    def test_moving_toward_the_optimum_improves_expected_utility(self, reference) -> None:
        cov = reference.prior_covariance(UNIVERSE)
        optimum = optimize_portfolio(
            Objective.MEAN_VARIANCE,
            UNIVERSE,
            cov,
            mu=MU,
            constraints=Constraints(max_weight=0.35),
            risk_aversion=3.0,
        ).weights
        plan = self._plan(reference, optimum)
        assert plan.expected_utility_gain > 0

    def test_zero_price_symbols_are_skipped(self, reference) -> None:
        cov = reference.prior_covariance(UNIVERSE)
        prices = PRICES.copy()
        prices[2] = 0.0
        plan = build_plan(
            UNIVERSE,
            FEASIBLE_W0,
            np.array([0.20, 0.20, 0.15, 0.15, 0.15, 0.15]),
            FEASIBLE_W0,
            NAV,
            prices,
            cov,
            MU,
            CostModel.from_reference(reference),
            RebalanceSettings(min_trade_notional=0.0, no_trade_band_bps=0.0),
        )
        assert "GOOGL" not in {leg.symbol for leg in plan.legs}

    def test_payload_is_json_shaped(self, reference) -> None:
        plan = self._plan(reference, np.array([0.20, 0.20, 0.15, 0.15, 0.15, 0.15]))
        payload = plan.to_payload()
        for key in (
            "legs",
            "suppressedLegs",
            "currentWeights",
            "targetWeights",
            "turnoverPct",
            "estimatedCostUsd",
            "basketOrderRequest",
            "submitEnabled",
        ):
            assert key in payload


class TestBasketRequest:
    def test_matches_the_execution_engine_contract(self) -> None:
        legs = [
            TradeLeg("AAPL", "SELL", 1500.0, 1000.0, 500, 100_000.0, 15.0, 3.0),
            TradeLeg("NVDA", "BUY", 100.0, 300.0, 200, 86_000.0, 12.9, 2.1),
        ]
        request = to_basket_request(legs, "rebalance-test", RebalanceSettings())
        assert request["name"] == "rebalance-test"
        assert len(request["legs"]) == 2
        for leg in request["legs"]:
            assert set(leg) == {"symbol", "side", "orderType", "quantity", "tif"}
            assert leg["side"] in ("BUY", "SELL")
            assert leg["orderType"] == "MARKET"
            assert leg["tif"] == "DAY"
            assert isinstance(leg["quantity"], int)

    def test_never_emits_a_zero_quantity_leg(self) -> None:
        """``BasketLegRequest.quantity`` is annotated ``@Min(1)`` on the Java side."""
        legs = [TradeLeg("AAPL", "BUY", 0.0, 0.0, 0, 0.0, 0.0, 0.0)]
        request = to_basket_request(legs, "x", RebalanceSettings())
        assert request["legs"] == []

    def test_order_type_is_configurable(self) -> None:
        legs = [TradeLeg("AAPL", "BUY", 0.0, 10.0, 10, 2000.0, 1.0, 0.1)]
        request = to_basket_request(legs, "x", RebalanceSettings(order_type="LIMIT"))
        assert request["legs"][0]["orderType"] == "LIMIT"


class TestSummarizeCost:
    def test_reports_current_and_target_volatility(self, reference) -> None:
        cov = reference.prior_covariance(UNIVERSE)
        target = np.array([0.20, 0.20, 0.15, 0.15, 0.15, 0.15])
        plan = build_plan(
            UNIVERSE,
            FEASIBLE_W0,
            target,
            target,
            NAV,
            PRICES,
            cov,
            MU,
            CostModel.from_reference(reference),
            RebalanceSettings(),
        )
        summary = summarize_cost(plan, cov)
        assert summary["currentVolatility"] > 0
        assert summary["targetVolatility"] > 0
        assert summary["legCount"] == len(plan.legs)
        assert "costBpsOfNav" in summary
