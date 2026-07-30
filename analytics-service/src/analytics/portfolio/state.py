"""Live portfolio state, rebuilt from the ``positions.updates`` Kafka stream.

The order-manager is the system of record; it publishes a ``PositionSnapshot`` per symbol on
every fill (``symbol, netQuantity, avgEntryPrice, realizedPnl, unrealizedPnl, lastMarkPrice,
timestamp``). This module keeps the latest snapshot per symbol and derives the quantities the
construction and risk layers need: marked notionals, weights and NAV.

**Mark price precedence** — live market data first, then the snapshot's own mark, then the
average entry price. A stale mark quietly rescales every weight in the book, so the resolution
order is explicit and reported per position.

**NAV** — MariaAlpha has no cash ledger, so NAV is ``sum |marked notional| + base_nav`` where
``base_nav`` is configuration, not accounting. Every response carries ``navSource`` saying so;
weights computed against a made-up denominator should never be mistaken for a real book.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

import numpy as np

if TYPE_CHECKING:
    from collections.abc import Callable, Sequence

    from analytics.numeric import FloatArray

NAV_SOURCE = "config-cash + marked-positions"


@dataclass(slots=True, frozen=True)
class Position:
    """One symbol's marked position."""

    symbol: str
    quantity: float
    avg_entry_price: float
    mark_price: float
    mark_source: str
    realized_pnl: float = 0.0
    unrealized_pnl: float = 0.0
    updated_at: str | None = None

    @property
    def notional(self) -> float:
        """Signed notional — negative for a short. The sign is the whole point."""
        return self.quantity * self.mark_price

    def to_payload(self) -> dict[str, Any]:
        return {
            "symbol": self.symbol,
            "quantity": self.quantity,
            "avgEntryPrice": self.avg_entry_price,
            "markPrice": self.mark_price,
            "markSource": self.mark_source,
            "notionalUsd": self.notional,
            "realizedPnl": self.realized_pnl,
            "unrealizedPnl": self.unrealized_pnl,
            "updatedAt": self.updated_at,
        }


@dataclass(slots=True, frozen=True)
class PortfolioSnapshot:
    """An immutable view of the book at one instant."""

    positions: tuple[Position, ...]
    nav_usd: float
    gross_exposure_usd: float
    net_exposure_usd: float
    base_nav_usd: float

    @property
    def symbols(self) -> tuple[str, ...]:
        return tuple(p.symbol for p in self.positions)

    def notionals(self) -> FloatArray:
        return np.array([p.notional for p in self.positions], dtype=np.float64)

    def weights(self) -> FloatArray:
        """Signed notional weights against NAV. Sums to net exposure / NAV, not to 1."""
        if self.nav_usd <= 0:
            return np.zeros(len(self.positions), dtype=np.float64)
        return self.notionals() / self.nav_usd

    def prices(self) -> dict[str, float]:
        return {p.symbol: p.mark_price for p in self.positions}

    def to_payload(self) -> dict[str, Any]:
        return {
            "positions": [p.to_payload() for p in self.positions],
            "navUsd": self.nav_usd,
            "baseNavUsd": self.base_nav_usd,
            "grossExposureUsd": self.gross_exposure_usd,
            "netExposureUsd": self.net_exposure_usd,
            "navSource": NAV_SOURCE,
            "positionCount": len(self.positions),
        }


class PortfolioState:
    """Thread-safe latest-snapshot-per-symbol store."""

    def __init__(
        self,
        base_nav: float = 1_000_000.0,
        price_lookup: Callable[[str], float | None] | None = None,
    ) -> None:
        self._base_nav = base_nav
        self._price_lookup = price_lookup
        self._lock = threading.RLock()
        self._raw: dict[str, dict[str, Any]] = {}

    def apply(self, snapshot: dict[str, Any]) -> bool:
        """Record a ``positions.updates`` payload. Returns False when it is unusable."""
        symbol = snapshot.get("symbol")
        if not isinstance(symbol, str) or not symbol:
            return False
        quantity = _as_float(snapshot.get("netQuantity"))
        if quantity is None:
            return False
        with self._lock:
            self._raw[symbol] = {
                "quantity": quantity,
                "avgEntryPrice": _as_float(snapshot.get("avgEntryPrice")) or 0.0,
                "lastMarkPrice": _as_float(snapshot.get("lastMarkPrice")),
                "realizedPnl": _as_float(snapshot.get("realizedPnl")) or 0.0,
                "unrealizedPnl": _as_float(snapshot.get("unrealizedPnl")) or 0.0,
                "timestamp": snapshot.get("timestamp"),
            }
        return True

    def snapshot(self) -> PortfolioSnapshot:
        """Mark the book and derive NAV / exposures."""
        with self._lock:
            raw = dict(self._raw)
        positions = [self._mark(symbol, row) for symbol, row in sorted(raw.items())]
        return self._assemble(positions)

    def _mark(self, symbol: str, row: dict[str, Any]) -> Position:
        live = self._price_lookup(symbol) if self._price_lookup is not None else None
        if live is not None and live > 0:
            mark, source = float(live), "market-data"
        elif row["lastMarkPrice"] is not None and row["lastMarkPrice"] > 0:
            mark, source = float(row["lastMarkPrice"]), "position-snapshot"
        else:
            mark, source = float(row["avgEntryPrice"]), "avg-entry-price"
        return Position(
            symbol=symbol,
            quantity=float(row["quantity"]),
            avg_entry_price=float(row["avgEntryPrice"]),
            mark_price=mark,
            mark_source=source,
            realized_pnl=float(row["realizedPnl"]),
            unrealized_pnl=float(row["unrealizedPnl"]),
            updated_at=row["timestamp"] if isinstance(row["timestamp"], str) else None,
        )

    def _assemble(self, positions: Sequence[Position]) -> PortfolioSnapshot:
        gross = float(sum(abs(p.notional) for p in positions))
        net = float(sum(p.notional for p in positions))
        return PortfolioSnapshot(
            positions=tuple(positions),
            nav_usd=gross + self._base_nav,
            gross_exposure_usd=gross,
            net_exposure_usd=net,
            base_nav_usd=self._base_nav,
        )

    def from_explicit(self, rows: Sequence[dict[str, Any]]) -> PortfolioSnapshot:
        """Build a snapshot from a caller-supplied book — what-if analysis and tests.

        Accepts ``{symbol, quantity, price}``. Price falls back to live market data, then to
        the reference price of an existing position for the same symbol.
        """
        with self._lock:
            known = dict(self._raw)
        positions: list[Position] = []
        for row in rows:
            symbol = str(row.get("symbol", "")).strip().upper()
            if not symbol:
                raise ValueError("each position needs a non-empty symbol")
            quantity = _as_float(row.get("quantity"))
            if quantity is None:
                raise ValueError(f"position for {symbol} needs a numeric quantity")
            price = _as_float(row.get("price"))
            if price is None or price <= 0:
                live = self._price_lookup(symbol) if self._price_lookup is not None else None
                if live is not None and live > 0:
                    price, source = float(live), "market-data"
                elif symbol in known and known[symbol]["lastMarkPrice"]:
                    price, source = float(known[symbol]["lastMarkPrice"]), "position-snapshot"
                else:
                    raise ValueError(
                        f"no price available for {symbol}; supply an explicit positive price"
                    )
            else:
                source = "supplied"
            positions.append(
                Position(
                    symbol=symbol,
                    quantity=float(quantity),
                    avg_entry_price=float(price),
                    mark_price=float(price),
                    mark_source=source,
                )
            )
        return self._assemble(positions)

    def count(self) -> int:
        with self._lock:
            return len(self._raw)


def _as_float(value: Any) -> float | None:
    """Coerce a JSON scalar to float. Kafka payloads carry BigDecimals as strings."""
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int | float):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return None
    return None
