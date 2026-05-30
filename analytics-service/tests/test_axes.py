"""Unit tests for the axe matcher (issue 2.2.6)."""

from __future__ import annotations

import pytest

from analytics.axes.matcher import AxeMatcher, IncomingLeg


def _matcher(default_ttl: int = 3600, min_match: int = 1):
    clock = [1000.0]

    def now() -> float:
        return clock[0]

    m = AxeMatcher(default_ttl_seconds=default_ttl, min_match_quantity=min_match, clock=now)
    return m, clock


def test_publish_registers_axe_with_expected_fields():
    m, _ = _matcher()
    axe = m.publish("a1", "clientA", "AAPL", "BUY", 1000, limit_price=99.5, ttl_seconds=600)
    assert axe.axe_id == "a1"
    assert axe.client_id == "clientA"
    assert axe.symbol == "AAPL"
    assert axe.side == "BUY"
    assert axe.quantity == 1000
    assert axe.remaining == 1000
    assert axe.limit_price == 99.5
    assert axe.refresh_count == 0


def test_publish_with_same_client_symbol_side_refreshes_instead_of_duplicating():
    m, clock = _matcher()
    m.publish("a1", "clientA", "AAPL", "BUY", 1000)
    clock[0] = 1500.0
    refreshed = m.publish("a-NEW", "clientA", "AAPL", "BUY", 2000)
    # axe_id stays as the original; refresh_count bumped.
    assert refreshed.axe_id == "a1"
    assert refreshed.quantity == 2000
    assert refreshed.refresh_count == 1
    # Snapshot should show exactly one entry.
    assert len(m.snapshot()) == 1


def test_publish_rejects_invalid_side_and_quantity():
    m, _ = _matcher()
    with pytest.raises(ValueError):
        m.publish("a1", "c", "X", "HOLD", 100)
    with pytest.raises(ValueError):
        m.publish("a2", "c", "X", "BUY", 0)


def test_cancel_removes_axe_and_refresh_index():
    m, _ = _matcher()
    m.publish("a1", "clientA", "AAPL", "BUY", 1000)
    assert m.cancel("a1") is True
    assert m.cancel("a1") is False
    # Re-publishing must create a fresh axe (refresh index was cleared).
    fresh = m.publish("a2", "clientA", "AAPL", "BUY", 500)
    assert fresh.axe_id == "a2"
    assert fresh.refresh_count == 0


def test_match_opposite_side_returns_suggestion_and_debits_remaining():
    m, _ = _matcher()
    m.publish("a1", "clientA", "AAPL", "SELL", 1000)
    matches = m.match(IncomingLeg("o1", "AAPL", "BUY", 400))
    assert len(matches) == 1
    assert matches[0].matched_quantity == 400
    assert matches[0].axe_remaining_before == 1000
    snap = m.snapshot()
    assert snap[0]["remaining"] == 600


def test_match_same_side_returns_no_suggestion():
    m, _ = _matcher()
    m.publish("a1", "clientA", "AAPL", "BUY", 1000)
    assert m.match(IncomingLeg("o1", "AAPL", "BUY", 100)) == []


def test_match_different_symbol_returns_no_suggestion():
    m, _ = _matcher()
    m.publish("a1", "clientA", "AAPL", "SELL", 1000)
    assert m.match(IncomingLeg("o1", "MSFT", "BUY", 100)) == []


def test_fully_consumed_axe_is_removed():
    m, _ = _matcher()
    m.publish("a1", "clientA", "AAPL", "SELL", 500)
    matches = m.match(IncomingLeg("o1", "AAPL", "BUY", 500))
    assert matches[0].matched_quantity == 500
    assert m.snapshot() == []
    # Republishing for same client should now create a brand-new axe.
    fresh = m.publish("a2", "clientA", "AAPL", "SELL", 200)
    assert fresh.axe_id == "a2"
    assert fresh.refresh_count == 0


def test_match_ranks_by_confidence_then_remaining():
    m, clock = _matcher(default_ttl=1000)
    # Old axe published at t=1000, fresh axe at t=1500. Both expire at +1000 from publish.
    m.publish("old", "C1", "AAPL", "SELL", 500)
    clock[0] = 1500.0
    m.publish("fresh", "C2", "AAPL", "SELL", 500)
    # Match at t=1500: old has 500s of 1000 TTL remaining (conf=0.5);
    # fresh has 1000s of 1000 TTL remaining (conf=1.0). Fresh should rank first.
    matches = m.match(IncomingLeg("o1", "AAPL", "BUY", 800))
    assert [s.axe_id for s in matches] == ["fresh", "old"]
    # First fill: 500 from fresh; second: 300 from old.
    assert matches[0].matched_quantity == 500
    assert matches[1].matched_quantity == 300


def test_expired_axe_is_dropped_at_match_time():
    m, clock = _matcher(default_ttl=100)
    m.publish("a1", "C1", "AAPL", "SELL", 500)
    clock[0] = 1200.0  # 200s after publish → past TTL
    matches = m.match(IncomingLeg("o1", "AAPL", "BUY", 100))
    assert matches == []
    assert m.snapshot() == []


def test_min_match_quantity_filters_tiny_orders():
    m, _ = _matcher(min_match=100)
    m.publish("a1", "C1", "AAPL", "SELL", 1000)
    # Incoming below min_match → skipped entirely.
    assert m.match(IncomingLeg("o1", "AAPL", "BUY", 50)) == []
    # Axe remaining is untouched.
    assert m.snapshot()[0]["remaining"] == 1000


def test_snapshot_filters_by_symbol_and_side():
    m, _ = _matcher()
    m.publish("a1", "C1", "AAPL", "BUY", 100)
    m.publish("a2", "C1", "AAPL", "SELL", 100)
    m.publish("a3", "C1", "MSFT", "BUY", 100)
    assert {r["axeId"] for r in m.snapshot(symbol="AAPL")} == {"a1", "a2"}
    assert {r["axeId"] for r in m.snapshot(symbol="AAPL", side="SELL")} == {"a2"}
    assert {r["axeId"] for r in m.snapshot(side="BUY")} == {"a1", "a3"}


def test_stats_tracks_active_count_and_matched_total():
    m, _ = _matcher()
    m.publish("a1", "C1", "AAPL", "SELL", 1000)
    m.match(IncomingLeg("o1", "AAPL", "BUY", 250))
    stats = m.stats()
    assert stats["activeAxes"] == 1
    assert stats["matchedTotalShares"] == 250
