"""Unit tests for the flow-toxicity detector."""

from __future__ import annotations

import pytest

from analytics.toxicity.detector import FillRecord, FlowToxicityDetector


def _make_detector(
    price_at_horizon: dict[tuple[str, float], float],
    *,
    horizons: tuple[int, ...] = (60,),
    threshold_bps: float = 5.0,
    min_obs: int = 1,
    alert_publisher=None,
) -> tuple[FlowToxicityDetector, list[float]]:
    """Build a detector whose price lookup is a static dict; returns clock advancer."""

    clock_value = [0.0]

    def clock() -> float:
        return clock_value[0]

    def price_lookup(symbol: str, ts: float) -> float | None:
        # Find the closest pre-recorded horizon price for this symbol/ts.
        return price_at_horizon.get((symbol, ts))

    det = FlowToxicityDetector(
        horizons_seconds=horizons,
        threshold_bps=threshold_bps,
        min_observations=min_obs,
        price_lookup=price_lookup,
        alert_publisher=alert_publisher,
        clock=clock,
    )
    return det, clock_value


def test_buy_followed_by_drop_is_adverse_selection_positive_markout():
    det, clock = _make_detector({("AAPL", 60.0): 99.0})
    det.on_fill(FillRecord("o1", "VWAP", "AAPL", "BUY", 100.0, 0.0))
    clock[0] = 61.0
    assert det.tick() == 1
    snap = det.snapshot()
    assert snap[0]["strategy"] == "VWAP"
    # Buy at 100, price fell to 99 → markout = +100bps (we were picked off).
    assert snap[0]["meanMarkoutBps"] == pytest.approx(100.0)
    assert snap[0]["toxic"] is True


def test_sell_followed_by_rally_is_adverse_selection_positive_markout():
    det, clock = _make_detector({("AAPL", 60.0): 101.0})
    det.on_fill(FillRecord("o1", "VWAP", "AAPL", "SELL", 100.0, 0.0))
    clock[0] = 61.0
    det.tick()
    snap = det.snapshot()
    # Sell at 100, price rose to 101 → markout = +100bps.
    assert snap[0]["meanMarkoutBps"] == pytest.approx(100.0)


def test_buy_followed_by_rally_is_favourable_negative_markout():
    det, clock = _make_detector({("AAPL", 60.0): 101.0})
    det.on_fill(FillRecord("o1", "VWAP", "AAPL", "BUY", 100.0, 0.0))
    clock[0] = 61.0
    det.tick()
    snap = det.snapshot()
    # Buy at 100, price rose to 101 → markout = -100bps (favourable).
    assert snap[0]["meanMarkoutBps"] == pytest.approx(-100.0)
    assert snap[0]["toxic"] is False


def test_pending_fill_below_horizon_not_consumed():
    det, clock = _make_detector({("AAPL", 60.0): 99.0})
    det.on_fill(FillRecord("o1", "VWAP", "AAPL", "BUY", 100.0, 0.0))
    clock[0] = 30.0  # not yet at the 60s horizon
    assert det.tick() == 0
    assert det.pending_count() == 1
    clock[0] = 60.0
    assert det.tick() == 1
    assert det.pending_count() == 0


def test_missing_horizon_price_skips_fill_silently():
    det, clock = _make_detector({})  # no horizon price for anything
    det.on_fill(FillRecord("o1", "VWAP", "AAPL", "BUY", 100.0, 0.0))
    clock[0] = 60.0
    det.tick()  # should not raise; should not record a stat
    assert det.snapshot() == []


def test_alert_fires_when_threshold_breached_after_min_observations():
    alerts: list[dict] = []
    det, clock = _make_detector(
        {("AAPL", 60.0): 99.0, ("AAPL", 70.0): 99.0, ("AAPL", 80.0): 99.0},
        min_obs=3,
        threshold_bps=10.0,
        alert_publisher=alerts.append,
    )
    for order_id, fill_ts in [("o1", 0.0), ("o2", 10.0), ("o3", 20.0)]:
        det.on_fill(FillRecord(order_id, "VWAP", "AAPL", "BUY", 100.0, fill_ts))
    clock[0] = 100.0
    det.tick()
    # 100bps mean markout, threshold 10bps → exactly one alert (cooldown blocks dupes).
    assert len(alerts) == 1
    assert alerts[0]["alertType"] == "FLOW_TOXICITY"
    assert alerts[0]["strategy"] == "VWAP"
    assert alerts[0]["meanMarkoutBps"] == pytest.approx(100.0)


def test_alert_suppressed_below_min_observations():
    alerts: list[dict] = []
    det, clock = _make_detector(
        {("AAPL", 60.0): 99.0},
        min_obs=5,
        alert_publisher=alerts.append,
    )
    det.on_fill(FillRecord("o1", "VWAP", "AAPL", "BUY", 100.0, 0.0))
    clock[0] = 60.0
    det.tick()
    assert alerts == []


def test_alert_filters_by_strategy_in_snapshot():
    det, clock = _make_detector(
        {("AAPL", 60.0): 99.0, ("MSFT", 60.0): 101.0},
    )
    det.on_fill(FillRecord("o1", "VWAP", "AAPL", "BUY", 100.0, 0.0))
    det.on_fill(FillRecord("o2", "MOMENTUM", "MSFT", "BUY", 100.0, 0.0))
    clock[0] = 60.0
    det.tick()
    rows = det.snapshot(strategy="VWAP")
    assert len(rows) == 1
    assert rows[0]["strategy"] == "VWAP"


def test_constructor_rejects_invalid_args():
    with pytest.raises(ValueError):
        FlowToxicityDetector(
            horizons_seconds=(),
            threshold_bps=5.0,
            min_observations=1,
            price_lookup=lambda *_: None,
        )
    with pytest.raises(ValueError):
        FlowToxicityDetector(
            horizons_seconds=(60,),
            threshold_bps=0.0,
            min_observations=1,
            price_lookup=lambda *_: None,
        )
