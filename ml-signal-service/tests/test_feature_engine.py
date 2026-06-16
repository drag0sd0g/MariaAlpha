"""Tests for the FeatureEngine."""

from __future__ import annotations

import queue
from datetime import UTC

from ml_signal.features.engine import FEATURE_NAMES, FeatureEngine


def _make_trade_tick(symbol: str, price: float, volume: int, epoch_seconds: float) -> dict:
    """Helper to create a TRADE tick dict."""
    from datetime import datetime

    ts = datetime.fromtimestamp(epoch_seconds, tz=UTC).isoformat()
    return {
        "symbol": symbol,
        "timestamp": ts,
        "eventType": "TRADE",
        "price": price,
        "size": volume,
        "bidPrice": 0,
        "askPrice": 0,
        "bidSize": 0,
        "askSize": 0,
        "vwap": 0,
        "dataSource": "ALPACA",
        "stale": False,
    }


def _make_quote_tick(symbol: str, bid: float, ask: float, epoch_seconds: float) -> dict:
    from datetime import datetime

    ts = datetime.fromtimestamp(epoch_seconds, tz=UTC).isoformat()
    return {
        "symbol": symbol,
        "timestamp": ts,
        "eventType": "QUOTE",
        "price": 0,
        "size": 0,
        "bidPrice": bid,
        "askPrice": ask,
        "bidSize": 100,
        "askSize": 100,
        "vwap": 0,
        "dataSource": "ALPACA",
        "stale": False,
    }


def _feed_n_bars(engine: FeatureEngine, symbol: str, n: int, start_price: float = 100.0) -> None:
    """Feed enough ticks to complete n bars (at 60s intervals)."""
    base_ts = 1700000000.0
    for i in range(n + 1):
        price = start_price + i * 0.1
        ts = base_ts + i * 60
        engine.on_tick(_make_trade_tick(symbol, price, 1000, ts))
        engine.on_tick(_make_trade_tick(symbol, price + 0.05, 500, ts + 30))


class TestFeatureEngineBasic:
    def test_no_features_before_min_bars(self, feature_engine: FeatureEngine) -> None:
        """Features should not be available until min_bars bars have been completed."""
        _feed_n_bars(feature_engine, "AAPL", 3)
        assert feature_engine.get_features("AAPL") is None

    def test_features_available_after_min_bars(self, feature_engine: FeatureEngine) -> None:
        """After enough bars, features should be available."""
        _feed_n_bars(feature_engine, "AAPL", 6)
        features = feature_engine.get_features("AAPL")
        assert features is not None

    def test_all_feature_names_present(self, feature_engine: FeatureEngine) -> None:
        """All 15 features should be in the returned dict."""
        _feed_n_bars(feature_engine, "AAPL", 6)
        features = feature_engine.get_features("AAPL")
        assert features is not None
        for name in FEATURE_NAMES:
            assert name in features, f"Missing feature: {name}"

    def test_multiple_symbols_independent(self, feature_engine: FeatureEngine) -> None:
        """Different symbols maintain separate feature state."""
        _feed_n_bars(feature_engine, "AAPL", 6, start_price=150)
        _feed_n_bars(feature_engine, "MSFT", 6, start_price=300)
        aapl = feature_engine.get_features("AAPL")
        msft = feature_engine.get_features("MSFT")
        assert aapl is not None and msft is not None
        assert aapl["ema_20"] != msft["ema_20"]

    def test_symbols_with_features(self, feature_engine: FeatureEngine) -> None:
        _feed_n_bars(feature_engine, "AAPL", 6)
        assert "AAPL" in feature_engine.symbols_with_features()

    def test_quote_ticks_update_price(self, feature_engine: FeatureEngine) -> None:
        """QUOTE ticks should contribute to bar prices via midpoint."""
        base_ts = 1700000000.0
        for i in range(7):
            ts = base_ts + i * 60
            feature_engine.on_tick(_make_quote_tick("SPY", 400.0 + i, 401.0 + i, ts))
        features = feature_engine.get_features("SPY")
        assert features is not None
        assert features["ema_20"] > 0


class TestFeatureEngineListeners:
    def test_listener_notified_on_feature_update(self, feature_engine: FeatureEngine) -> None:
        q: queue.Queue[tuple[str, dict[str, float]]] = queue.Queue()
        feature_engine.add_listener(q, symbols={"AAPL"})
        _feed_n_bars(feature_engine, "AAPL", 6)
        assert not q.empty()
        symbol, features = q.get_nowait()
        assert symbol == "AAPL"
        assert "rsi_14" in features

    def test_listener_filtered_by_symbol(self, feature_engine: FeatureEngine) -> None:
        q: queue.Queue[tuple[str, dict[str, float]]] = queue.Queue()
        feature_engine.add_listener(q, symbols={"MSFT"})
        _feed_n_bars(feature_engine, "AAPL", 6)
        assert q.empty()

    def test_remove_listener(self, feature_engine: FeatureEngine) -> None:
        q: queue.Queue[tuple[str, dict[str, float]]] = queue.Queue()
        feature_engine.add_listener(q)
        feature_engine.remove_listener(q)
        _feed_n_bars(feature_engine, "AAPL", 6)
        assert q.empty()


class TestGetBars:
    def test_unknown_symbol_returns_empty(self, feature_engine: FeatureEngine) -> None:
        assert feature_engine.get_bars("UNKNOWN") == []

    def test_returns_completed_bars(self, feature_engine: FeatureEngine) -> None:
        _feed_n_bars(feature_engine, "AAPL", 6)
        bars = feature_engine.get_bars("AAPL")
        assert len(bars) == 6
        assert bars[0].close > 0

    def test_n_truncates_to_most_recent(self, feature_engine: FeatureEngine) -> None:
        _feed_n_bars(feature_engine, "AAPL", 10)
        bars = feature_engine.get_bars("AAPL", n=3)
        assert len(bars) == 3
        full = feature_engine.get_bars("AAPL")
        assert bars == full[-3:]

    def test_returns_snapshot_not_live_view(self, feature_engine: FeatureEngine) -> None:
        """The list returned must not mutate when new bars arrive."""
        _feed_n_bars(feature_engine, "AAPL", 6)
        snapshot = feature_engine.get_bars("AAPL")
        before = len(snapshot)
        _feed_n_bars(feature_engine, "AAPL", 3, start_price=200)
        assert len(snapshot) == before
