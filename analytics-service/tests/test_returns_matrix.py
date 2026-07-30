"""Bar building and return-matrix alignment in ``MarketDataCache``.

These cover the reason the arrival clock exists: the simulated market-data tape loops a fixed CSV
without rewriting timestamps, so the event-time axis repeats forever and produces a degenerate
return series.
"""

from __future__ import annotations

import numpy as np
import pytest

from analytics.consumer.market_data import MarketDataCache


def fill(cache: MarketDataCache, symbol: str, prices: list[float], start: float = 1000.0) -> None:
    for i, price in enumerate(prices):
        ts = start + i * 60.0
        cache.record(symbol, ts, price, arrival_seconds=ts)


class TestBars:
    def test_buckets_by_the_requested_interval(self) -> None:
        cache = MarketDataCache()
        fill(cache, "AAPL", [100.0, 101.0, 102.0, 103.0])
        bars = cache.bars("AAPL", 120, clock="arrival")
        assert len(bars) == 2

    def test_keeps_the_last_price_in_each_bucket(self) -> None:
        cache = MarketDataCache()
        cache.record("AAPL", 0.0, 100.0, arrival_seconds=0.0)
        cache.record("AAPL", 10.0, 105.0, arrival_seconds=10.0)
        cache.record("AAPL", 20.0, 110.0, arrival_seconds=20.0)
        assert cache.bars("AAPL", 60, clock="arrival") == {0: 110.0}

    def test_ignores_non_positive_and_non_finite_prices(self) -> None:
        cache = MarketDataCache()
        cache.record("AAPL", 0.0, 100.0, arrival_seconds=0.0)
        cache.record("AAPL", 61.0, -5.0, arrival_seconds=61.0)
        cache.record("AAPL", 121.0, float("nan"), arrival_seconds=121.0)
        assert cache.bars("AAPL", 60, clock="arrival") == {0: 100.0}

    def test_event_clock_uses_the_tick_timestamp(self) -> None:
        cache = MarketDataCache()
        cache.record("AAPL", 5_000.0, 100.0, arrival_seconds=0.0)
        assert cache.bars("AAPL", 60, clock="event") == {83: 100.0}
        assert cache.bars("AAPL", 60, clock="arrival") == {0: 100.0}

    def test_arrival_clock_survives_a_looping_tape(self) -> None:
        """The tape replays the same four event timestamps forever; arrival time still advances."""
        cache = MarketDataCache()
        arrival = 0.0
        for _ in range(6):
            for event_ts, price in [(0.0, 100.0), (1.0, 101.0), (2.0, 102.0), (3.0, 103.0)]:
                cache.record("AAPL", event_ts, price, arrival_seconds=arrival)
                arrival += 30.0

        event_bars = cache.bars("AAPL", 60, clock="event")
        arrival_bars = cache.bars("AAPL", 60, clock="arrival")
        assert len(event_bars) == 1, "event time collapses to a single bucket"
        assert len(arrival_bars) > 1, "arrival time keeps producing distinct bars"

    def test_rejects_a_non_positive_interval(self) -> None:
        cache = MarketDataCache()
        with pytest.raises(ValueError, match="bar_seconds must be positive"):
            cache.bars("AAPL", 0)

    def test_unknown_symbol_yields_no_bars(self) -> None:
        assert MarketDataCache().bars("ZZZZ", 60) == {}


class TestReturnsMatrix:
    def test_shape_and_symbol_order(self) -> None:
        cache = MarketDataCache()
        fill(cache, "AAPL", [100.0 + i for i in range(10)])
        fill(cache, "MSFT", [400.0 + i for i in range(10)])
        returns, symbols = cache.returns_matrix(["AAPL", "MSFT"], 60)
        assert symbols == ("AAPL", "MSFT")
        assert returns.shape == (9, 2)

    def test_computes_log_returns(self) -> None:
        cache = MarketDataCache()
        fill(cache, "AAPL", [100.0, 110.0])
        returns, _ = cache.returns_matrix(["AAPL"], 60)
        assert returns[0, 0] == pytest.approx(np.log(1.1), rel=1e-12)

    def test_excludes_symbols_with_too_little_history(self) -> None:
        cache = MarketDataCache()
        fill(cache, "AAPL", [100.0 + i for i in range(10)])
        cache.record("MSFT", 1000.0, 400.0, arrival_seconds=1000.0)
        _, symbols = cache.returns_matrix(["AAPL", "MSFT"], 60)
        assert symbols == ("AAPL",)

    def test_forward_fills_a_symbol_that_ticks_less_often(self) -> None:
        cache = MarketDataCache()
        fill(cache, "AAPL", [100.0 + i for i in range(10)])
        for i in (0, 5, 9):
            cache.record("MSFT", 1000.0 + i * 60.0, 400.0 + i, arrival_seconds=1000.0 + i * 60.0)
        returns, symbols = cache.returns_matrix(["AAPL", "MSFT"], 60)
        assert symbols == ("AAPL", "MSFT")
        assert np.isfinite(returns).all()
        # Bars where MSFT did not trade carry a zero return rather than a gap.
        assert (returns[:, 1] == 0.0).any()

    def test_alignment_starts_where_every_symbol_has_data(self) -> None:
        cache = MarketDataCache()
        fill(cache, "AAPL", [100.0 + i for i in range(10)], start=1000.0)
        fill(cache, "MSFT", [400.0 + i for i in range(5)], start=1000.0 + 5 * 60.0)
        returns, symbols = cache.returns_matrix(["AAPL", "MSFT"], 60)
        assert symbols == ("AAPL", "MSFT")
        assert returns.shape[0] == 4

    def test_respects_max_bars(self) -> None:
        cache = MarketDataCache()
        fill(cache, "AAPL", [100.0 + i for i in range(50)])
        returns, _ = cache.returns_matrix(["AAPL"], 60, max_bars=10)
        assert returns.shape[0] == 10

    def test_empty_cache_returns_an_empty_matrix(self) -> None:
        returns, symbols = MarketDataCache().returns_matrix(["AAPL"], 60)
        assert returns.size == 0
        assert symbols == ()

    def test_single_bucket_returns_an_empty_matrix(self) -> None:
        cache = MarketDataCache()
        cache.record("AAPL", 0.0, 100.0, arrival_seconds=0.0)
        cache.record("AAPL", 1.0, 101.0, arrival_seconds=1.0)
        returns, symbols = cache.returns_matrix(["AAPL"], 3600)
        assert returns.size == 0
        assert symbols == ()

    def test_tracked_symbols_is_sorted(self) -> None:
        cache = MarketDataCache()
        for symbol in ("NVDA", "AAPL", "MSFT"):
            cache.record(symbol, 0.0, 100.0, arrival_seconds=0.0)
        assert cache.tracked_symbols() == ("AAPL", "MSFT", "NVDA")


class TestBackwardCompatibility:
    def test_record_still_works_without_an_explicit_arrival_time(self) -> None:
        cache = MarketDataCache()
        cache.record("AAPL", 1000.0, 100.0)
        assert cache.latest("AAPL") == 100.0
        assert cache.price_at("AAPL", 1000.0) == 100.0

    def test_price_at_still_reads_the_event_axis(self) -> None:
        cache = MarketDataCache()
        cache.record("AAPL", 1000.0, 100.0, arrival_seconds=0.0)
        cache.record("AAPL", 2000.0, 200.0, arrival_seconds=1.0)
        assert cache.price_at("AAPL", 1500.0) == 100.0
        assert cache.price_at("AAPL", 2500.0) == 200.0
        assert cache.price_at("AAPL", 500.0) is None
