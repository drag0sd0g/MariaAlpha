"""Unit tests for the PnL-attribution engine (issue 2.2.5)."""

from __future__ import annotations

from datetime import datetime

import pytest

from analytics.pnl.attribution import PnlAttributionEngine, TcaInput


def _tca(
    *,
    order_id: str = "o1",
    strategy: str = "VWAP",
    symbol: str = "AAPL",
    side: str = "BUY",
    quantity: float = 100.0,
    arrival_price: float = 100.0,
    realized_avg_price: float = 100.10,
    vwap_benchmark_price: float = 100.05,
    spread_cost_bps: float = 5.0,
    commission_total: float | None = None,
    computed_at: datetime | None = None,
    decision_mid_price: float | None = None,
) -> TcaInput:
    return TcaInput(
        order_id=order_id,
        strategy=strategy,
        symbol=symbol,
        side=side,
        quantity=quantity,
        arrival_price=arrival_price,
        realized_avg_price=realized_avg_price,
        vwap_benchmark_price=vwap_benchmark_price,
        spread_cost_bps=spread_cost_bps,
        commission_total=commission_total,
        computed_at=computed_at or datetime(2026, 5, 30, 14, 30, 0),
        decision_mid_price=decision_mid_price,
    )


def test_buy_attribution_math_components_sum_to_realized():
    eng = PnlAttributionEngine(commission_bps=0.5)
    row = eng.attribute(_tca())
    # Realized = (100.05 − 100.10) × +100 = −5.00.
    assert row.realized_pnl_usd == pytest.approx(-5.0)
    # Spread = −(5/10000) × 100 × 100 = −5.00.
    assert row.spread_usd == pytest.approx(-5.0)
    # Market = (100.05 − 100) × +100 = +5.00.
    assert row.market_usd == pytest.approx(5.0)
    # Commission = −(0.5/10000) × 100.10 × 100 ≈ −0.5005.
    assert row.commission_usd == pytest.approx(-0.5005, rel=1e-4)
    # Components must reconstruct realized (within float rounding).
    assert row.total() == pytest.approx(row.realized_pnl_usd, rel=1e-6, abs=1e-3)


def test_sell_signed_quantity_flips_market_component_sign():
    eng = PnlAttributionEngine()
    row = eng.attribute(_tca(side="SELL"))
    # For a sell with vwap > arrival, market drift hurts us → negative.
    assert row.market_usd == pytest.approx(-5.0)


def test_explicit_commission_total_overrides_bps_estimate():
    eng = PnlAttributionEngine(commission_bps=0.5)
    row = eng.attribute(_tca(commission_total=2.50))
    assert row.commission_usd == pytest.approx(-2.50)
    # Sign forced negative even if caller passed positive number.
    row2 = eng.attribute(_tca(order_id="o2", commission_total=-7.0))
    assert row2.commission_usd == pytest.approx(-7.0)


def test_residual_captures_realized_minus_components():
    eng = PnlAttributionEngine()
    row = eng.attribute(_tca())
    expected_residual = row.realized_pnl_usd - (
        row.spread_usd + row.timing_usd + row.market_usd + row.commission_usd
    )
    assert row.residual_usd == pytest.approx(expected_residual, abs=1e-3)


def test_daily_summary_rolls_up_per_strategy_per_day():
    eng = PnlAttributionEngine()
    day1 = datetime(2026, 5, 30, 10, 0, 0)
    day2 = datetime(2026, 5, 31, 10, 0, 0)
    eng.attribute(_tca(order_id="o1", strategy="VWAP", computed_at=day1))
    eng.attribute(_tca(order_id="o2", strategy="VWAP", computed_at=day1))
    eng.attribute(_tca(order_id="o3", strategy="TWAP", computed_at=day1))
    eng.attribute(_tca(order_id="o4", strategy="VWAP", computed_at=day2))

    rows = eng.daily_summary()
    # 3 unique (strategy, day) buckets.
    assert len(rows) == 3
    vwap_d1 = next(r for r in rows if r["strategy"] == "VWAP" and r["date"] == "2026-05-30")
    assert vwap_d1["orders"] == 2


def test_daily_summary_strategy_filter():
    eng = PnlAttributionEngine()
    eng.attribute(_tca(order_id="o1", strategy="VWAP"))
    eng.attribute(_tca(order_id="o2", strategy="TWAP"))
    rows = eng.daily_summary(strategy="VWAP")
    assert len(rows) == 1
    assert rows[0]["strategy"] == "VWAP"


def test_order_breakdown_returns_none_for_unknown_order():
    eng = PnlAttributionEngine()
    assert eng.order_breakdown("nope") is None


def test_order_breakdown_returns_dict_for_known_order():
    eng = PnlAttributionEngine()
    eng.attribute(_tca(order_id="o1"))
    row = eng.order_breakdown("o1")
    assert row is not None
    assert row["orderId"] == "o1"
    assert "spreadUsd" in row and "marketUsd" in row and "realizedPnlUsd" in row


def test_strategy_distribution_empty_for_unknown_strategy():
    eng = PnlAttributionEngine()
    dist = eng.strategy_distribution("NOPE")
    assert dist == {"strategy": "NOPE", "orders": 0, "components": {}}


def test_strategy_distribution_reports_min_median_max_sum():
    eng = PnlAttributionEngine()
    # Three different realized PnLs by varying vwap_benchmark vs realized.
    for i, vwap in enumerate([100.05, 100.10, 100.15]):
        eng.attribute(
            _tca(
                order_id=f"o{i}",
                strategy="VWAP",
                realized_avg_price=100.0,
                vwap_benchmark_price=vwap,
            )
        )
    dist = eng.strategy_distribution("VWAP")
    assert dist["orders"] == 3
    assert "realized" in dist["components"]
    realized = dist["components"]["realized"]
    # min/median/max should be sorted (sums of 5/10/15 USD).
    assert realized["min"] == pytest.approx(5.0)
    assert realized["median"] == pytest.approx(10.0)
    assert realized["max"] == pytest.approx(15.0)
    assert realized["sum"] == pytest.approx(30.0)


def test_timing_is_zero_when_decision_mid_absent():
    # No decision_mid_price → timing must collapse to 0 (legacy TCA rows).
    eng = PnlAttributionEngine()
    row = eng.attribute(_tca())
    assert row.timing_usd == pytest.approx(0.0)


def test_timing_uses_decision_mid_when_present_buy():
    # BUY 100 @ arrival=100.10, decision-mid=100.00 → market drifted up between decision
    # and arrival → timing = (100.10 − 100.00) × +100 = +10.00 (favorable).
    eng = PnlAttributionEngine()
    row = eng.attribute(_tca(arrival_price=100.10, decision_mid_price=100.00))
    assert row.timing_usd == pytest.approx(10.0)
    # Components must still reconstruct realized PnL (residual absorbs any drift).
    assert row.total() == pytest.approx(row.realized_pnl_usd, abs=1e-3)


def test_timing_sign_flips_for_sell():
    # SELL 100 @ arrival=99.90, decision-mid=100.00 → market dropped between decision
    # and arrival → timing = (99.90 − 100.00) × −100 = +10.00 (favorable for a short).
    eng = PnlAttributionEngine()
    row = eng.attribute(_tca(side="SELL", arrival_price=99.90, decision_mid_price=100.00))
    assert row.timing_usd == pytest.approx(10.0)


def test_all_rows_returns_all_attributions_as_dicts():
    eng = PnlAttributionEngine()
    eng.attribute(_tca(order_id="o1"))
    eng.attribute(_tca(order_id="o2"))
    rows = eng.all_rows()
    assert len(rows) == 2
    assert {r["orderId"] for r in rows} == {"o1", "o2"}
