"""Unit tests for the market-data cache (used by toxicity detector for markout lookups)."""

from __future__ import annotations

from analytics.consumer.market_data import MarketDataCache


def test_record_and_latest():
    c = MarketDataCache()
    c.record("AAPL", 100.0, 99.5)
    c.record("AAPL", 101.0, 99.7)
    assert c.latest("AAPL") == 99.7


def test_latest_returns_none_for_unknown_symbol():
    c = MarketDataCache()
    assert c.latest("NOPE") is None


def test_price_at_finds_closest_prior_observation():
    c = MarketDataCache()
    c.record("AAPL", 100.0, 99.0)
    c.record("AAPL", 110.0, 99.5)
    c.record("AAPL", 120.0, 100.0)
    assert c.price_at("AAPL", 110.0) == 99.5
    assert c.price_at("AAPL", 115.0) == 99.5
    assert c.price_at("AAPL", 200.0) == 100.0


def test_price_at_returns_none_before_first_observation():
    c = MarketDataCache()
    c.record("AAPL", 100.0, 99.0)
    assert c.price_at("AAPL", 50.0) is None


def test_price_at_returns_none_for_unknown_symbol():
    c = MarketDataCache()
    assert c.price_at("NOPE", 100.0) is None


def test_record_handles_out_of_order_ticks():
    c = MarketDataCache()
    c.record("AAPL", 100.0, 99.0)
    c.record("AAPL", 110.0, 99.5)
    c.record("AAPL", 105.0, 99.2)
    assert c.price_at("AAPL", 105.0) == 99.2
    assert c.price_at("AAPL", 110.0) == 99.5


def test_record_trims_history_when_max_exceeded():
    c = MarketDataCache(max_history_per_symbol=10)
    for i in range(15):
        c.record("AAPL", float(i), 100.0 + i)
    assert c.latest("AAPL") == 100.0 + 14
    assert c.price_at("AAPL", 14.0) == 100.0 + 14
