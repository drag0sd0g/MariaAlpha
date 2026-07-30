"""Portfolio state tests — mark-price precedence, NAV, weights, malformed payloads."""

from __future__ import annotations

import numpy as np
import pytest

from analytics.portfolio.state import NAV_SOURCE, PortfolioState, Position, _as_float


def snapshot_payload(symbol: str, quantity: float, **overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "symbol": symbol,
        "netQuantity": quantity,
        "avgEntryPrice": 100.0,
        "realizedPnl": 0.0,
        "unrealizedPnl": 0.0,
        "lastMarkPrice": 110.0,
        "timestamp": "2026-07-29T14:00:00Z",
    }
    payload.update(overrides)
    return payload


class TestAsFloat:
    @pytest.mark.parametrize(
        ("value", "expected"),
        [(1, 1.0), (1.5, 1.5), ("2.25", 2.25), ("-3", -3.0)],
    )
    def test_coerces_numeric_scalars_and_strings(self, value: object, expected: float) -> None:
        assert _as_float(value) == expected

    @pytest.mark.parametrize("value", [None, True, False, "abc", [], {}])
    def test_rejects_everything_else(self, value: object) -> None:
        assert _as_float(value) is None


class TestApply:
    def test_records_a_snapshot(self) -> None:
        state = PortfolioState(base_nav=0.0)
        assert state.apply(snapshot_payload("AAPL", 100)) is True
        assert state.count() == 1

    def test_later_snapshot_replaces_the_earlier_one(self) -> None:
        state = PortfolioState(base_nav=0.0)
        state.apply(snapshot_payload("AAPL", 100))
        state.apply(snapshot_payload("AAPL", 250))
        book = state.snapshot()
        assert state.count() == 1
        assert book.positions[0].quantity == 250

    def test_accepts_string_decimals_as_kafka_sends_them(self) -> None:
        state = PortfolioState(base_nav=0.0)
        assert state.apply(snapshot_payload("AAPL", "100.5", avgEntryPrice="99.25")) is True
        assert state.snapshot().positions[0].quantity == 100.5

    @pytest.mark.parametrize(
        "payload",
        [
            {},
            {"symbol": ""},
            {"symbol": "AAPL"},
            {"symbol": "AAPL", "netQuantity": "not-a-number"},
            {"netQuantity": 100},
        ],
    )
    def test_rejects_malformed_payloads_without_raising(self, payload: dict[str, object]) -> None:
        state = PortfolioState(base_nav=0.0)
        assert state.apply(payload) is False
        assert state.count() == 0


class TestMarkPricePrecedence:
    def test_prefers_live_market_data(self) -> None:
        state = PortfolioState(base_nav=0.0, price_lookup=lambda _s: 200.0)
        state.apply(snapshot_payload("AAPL", 100))
        position = state.snapshot().positions[0]
        assert position.mark_price == 200.0
        assert position.mark_source == "market-data"

    def test_falls_back_to_the_snapshot_mark(self) -> None:
        state = PortfolioState(base_nav=0.0, price_lookup=lambda _s: None)
        state.apply(snapshot_payload("AAPL", 100))
        position = state.snapshot().positions[0]
        assert position.mark_price == 110.0
        assert position.mark_source == "position-snapshot"

    def test_falls_back_to_the_average_entry_price(self) -> None:
        state = PortfolioState(base_nav=0.0)
        state.apply(snapshot_payload("AAPL", 100, lastMarkPrice=None))
        position = state.snapshot().positions[0]
        assert position.mark_price == 100.0
        assert position.mark_source == "avg-entry-price"

    def test_ignores_a_non_positive_live_price(self) -> None:
        state = PortfolioState(base_nav=0.0, price_lookup=lambda _s: 0.0)
        state.apply(snapshot_payload("AAPL", 100))
        assert state.snapshot().positions[0].mark_source == "position-snapshot"


class TestSnapshot:
    def test_nav_is_gross_exposure_plus_the_configured_cash_base(self) -> None:
        state = PortfolioState(base_nav=1_000_000.0, price_lookup=lambda _s: 100.0)
        state.apply(snapshot_payload("AAPL", 1000))
        state.apply(snapshot_payload("MSFT", -500))
        book = state.snapshot()
        assert book.gross_exposure_usd == pytest.approx(150_000.0)
        assert book.net_exposure_usd == pytest.approx(50_000.0)
        assert book.nav_usd == pytest.approx(1_150_000.0)

    def test_weights_are_signed_and_scale_by_nav(self) -> None:
        state = PortfolioState(base_nav=0.0, price_lookup=lambda _s: 100.0)
        state.apply(snapshot_payload("AAPL", 1000))
        state.apply(snapshot_payload("MSFT", -500))
        book = state.snapshot()
        assert np.allclose(book.weights(), [100_000 / 150_000, -50_000 / 150_000])

    def test_weights_are_zero_when_nav_is_zero(self) -> None:
        state = PortfolioState(base_nav=0.0)
        assert np.allclose(state.snapshot().weights(), [])

    def test_positions_are_sorted_for_a_stable_symbol_order(self) -> None:
        state = PortfolioState(base_nav=0.0, price_lookup=lambda _s: 100.0)
        for symbol in ("NVDA", "AAPL", "MSFT"):
            state.apply(snapshot_payload(symbol, 100))
        assert state.snapshot().symbols == ("AAPL", "MSFT", "NVDA")

    def test_payload_declares_where_nav_came_from(self) -> None:
        state = PortfolioState(base_nav=500.0)
        payload = state.snapshot().to_payload()
        assert payload["navSource"] == NAV_SOURCE
        assert payload["baseNavUsd"] == 500.0
        assert payload["positionCount"] == 0

    def test_prices_map(self) -> None:
        state = PortfolioState(base_nav=0.0, price_lookup=lambda _s: 42.0)
        state.apply(snapshot_payload("AAPL", 10))
        assert state.snapshot().prices() == {"AAPL": 42.0}


class TestFromExplicit:
    def test_builds_a_book_from_supplied_rows(self) -> None:
        state = PortfolioState(base_nav=0.0)
        book = state.from_explicit([{"symbol": "nvda", "quantity": 2000, "price": 500.0}])
        assert book.symbols == ("NVDA",)
        assert book.positions[0].notional == pytest.approx(1_000_000.0)
        assert book.positions[0].mark_source == "supplied"

    def test_falls_back_to_live_prices(self) -> None:
        state = PortfolioState(base_nav=0.0, price_lookup=lambda _s: 250.0)
        book = state.from_explicit([{"symbol": "AAPL", "quantity": 10}])
        assert book.positions[0].mark_price == 250.0
        assert book.positions[0].mark_source == "market-data"

    def test_falls_back_to_a_known_position_mark(self) -> None:
        state = PortfolioState(base_nav=0.0)
        state.apply(snapshot_payload("AAPL", 10))
        book = state.from_explicit([{"symbol": "AAPL", "quantity": 99}])
        assert book.positions[0].mark_price == 110.0

    def test_raises_when_no_price_can_be_found(self) -> None:
        state = PortfolioState(base_nav=0.0)
        with pytest.raises(ValueError, match="no price available for ZZZZ"):
            state.from_explicit([{"symbol": "ZZZZ", "quantity": 1}])

    def test_raises_on_a_missing_symbol(self) -> None:
        state = PortfolioState(base_nav=0.0)
        with pytest.raises(ValueError, match="non-empty symbol"):
            state.from_explicit([{"quantity": 1}])

    def test_raises_on_a_non_numeric_quantity(self) -> None:
        state = PortfolioState(base_nav=0.0)
        with pytest.raises(ValueError, match="numeric quantity"):
            state.from_explicit([{"symbol": "AAPL", "quantity": "many", "price": 1.0}])


class TestPosition:
    def test_notional_is_signed(self) -> None:
        short = Position("AAPL", -100.0, 90.0, 100.0, "supplied")
        assert short.notional == -10_000.0

    def test_payload_is_camel_case(self) -> None:
        payload = Position("AAPL", 10.0, 90.0, 100.0, "supplied").to_payload()
        assert payload["notionalUsd"] == 1000.0
        assert payload["markSource"] == "supplied"
