"""Client interest / axe matching model — issue 2.2.6.

Sell-side desks maintain *axes* — standing client expressions of interest in a symbol and side
("client X wants to buy up to 50,000 NVDA over the next hour"). The matcher tracks active
axes and, when desk flow arrives on the opposite side of the same symbol, surfaces a
*potential cross* the trader can offer to the axed client before routing externally.

The matcher provides three operations:

- **publish_axe** — register or refresh an axe. Each axe has a TTL after which it auto-expires.
- **match** — given an incoming order (symbol/side/quantity), return ranked counter-axes that
  could absorb some/all of the flow. Ranking is *price-time-weighted*: axes with a higher
  ``confidence`` (refreshed frequently by the client) score higher; ties broken by recency.
- **snapshot** — list active axes, optionally filtered by symbol/side. Used by the UI.

The confidence model is intentionally simple in MVP:

- New axe: confidence = 1.0.
- Each refresh of the same ``(client_id, symbol, side)`` triple keeps the confidence at 1.0
  and resets the TTL — this is the "active" signal.
- Time-decay: confidence decays linearly to 0 over the TTL, so a near-expired axe scores
  lower than a freshly-refreshed one.

When an incoming order is matched, the matched quantity is debited from the axe's remaining
size. An axe with zero remaining size is removed.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from collections.abc import Callable


@dataclass(slots=True)
class Axe:
    """An active client expression of interest."""

    axe_id: str
    client_id: str
    symbol: str
    side: str  # "BUY" or "SELL"
    quantity: int
    remaining: int
    limit_price: float | None
    published_at: float
    expires_at: float
    confidence: float = 1.0
    refresh_count: int = 0


@dataclass(slots=True)
class MatchSuggestion:
    """Suggested cross between an incoming order leg and an axed client."""

    axe_id: str
    client_id: str
    symbol: str
    axe_side: str
    matched_quantity: int
    axe_remaining_before: int
    confidence: float
    score: float
    limit_price: float | None


@dataclass(slots=True, frozen=True)
class IncomingLeg:
    """The minimum the matcher needs about an incoming order."""

    order_id: str
    symbol: str
    side: str  # "BUY" or "SELL"
    quantity: int


class AxeMatcher:
    """In-memory axe book with TTL and refresh-based confidence."""

    def __init__(
        self,
        default_ttl_seconds: int = 3600,
        min_match_quantity: int = 1,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self._default_ttl = default_ttl_seconds
        self._min_match = max(1, min_match_quantity)
        if clock is None:
            import time

            clock = time.time
        self._clock = clock
        self._lock = threading.RLock()
        # Keyed by axe_id so the matcher is robust to multiple axes per client/symbol.
        self._axes: dict[str, Axe] = {}
        # Refresh index keyed by (client_id, symbol, side) → axe_id for fast refresh lookup.
        self._refresh_index: dict[tuple[str, str, str], str] = {}
        self._matched_total = 0

    # -- writers ----------------------------------------------------------

    def publish(
        self,
        axe_id: str,
        client_id: str,
        symbol: str,
        side: str,
        quantity: int,
        limit_price: float | None = None,
        ttl_seconds: int | None = None,
    ) -> Axe:
        """Register a new axe or refresh an existing one with the same (client, symbol, side)."""
        if side not in ("BUY", "SELL"):
            raise ValueError(f"axe side must be BUY or SELL, got {side!r}")
        if quantity <= 0:
            raise ValueError("axe quantity must be positive")
        now = self._clock()
        ttl = ttl_seconds if ttl_seconds is not None else self._default_ttl
        with self._lock:
            key = (client_id, symbol, side)
            existing_id = self._refresh_index.get(key)
            if existing_id is not None and existing_id in self._axes:
                axe = self._axes[existing_id]
                # Refresh: keep the existing axe_id alive, bump quantity to max, reset TTL,
                # boost confidence back to 1.0.
                axe.quantity = max(axe.quantity, quantity)
                axe.remaining = max(axe.remaining, quantity)
                axe.limit_price = limit_price
                axe.published_at = now
                axe.expires_at = now + ttl
                axe.confidence = 1.0
                axe.refresh_count += 1
                return axe
            axe = Axe(
                axe_id=axe_id,
                client_id=client_id,
                symbol=symbol,
                side=side,
                quantity=quantity,
                remaining=quantity,
                limit_price=limit_price,
                published_at=now,
                expires_at=now + ttl,
                confidence=1.0,
                refresh_count=0,
            )
            self._axes[axe_id] = axe
            self._refresh_index[key] = axe_id
            return axe

    def cancel(self, axe_id: str) -> bool:
        with self._lock:
            axe = self._axes.pop(axe_id, None)
            if axe is None:
                return False
            self._refresh_index.pop((axe.client_id, axe.symbol, axe.side), None)
            return True

    # -- matching ---------------------------------------------------------

    def match(self, leg: IncomingLeg) -> list[MatchSuggestion]:
        """Return ranked match suggestions for an incoming order leg.

        Side semantics: an incoming BUY matches against ``SELL`` axes (and vice-versa). Each
        suggestion debits matched quantity from the axe immediately so a subsequent caller
        can't double-fill it.
        """
        if leg.quantity < self._min_match:
            return []
        opposing_side = "SELL" if leg.side == "BUY" else "BUY"
        now = self._clock()
        suggestions: list[MatchSuggestion] = []
        with self._lock:
            self._expire_locked(now)
            candidates = [
                a
                for a in self._axes.values()
                if a.symbol == leg.symbol and a.side == opposing_side and a.remaining > 0
            ]
            # Refresh-decay confidence so older axes score lower at match time.
            for axe in candidates:
                ttl_remaining = max(0.0, axe.expires_at - now)
                ttl_total = max(1.0, axe.expires_at - axe.published_at)
                axe.confidence = max(0.0, min(1.0, ttl_remaining / ttl_total))
            # Rank: higher confidence first, then larger remaining, then more refreshes.
            candidates.sort(
                key=lambda a: (
                    -a.confidence,
                    -a.remaining,
                    -a.refresh_count,
                    a.published_at,
                )
            )
            remaining_to_match = leg.quantity
            for axe in candidates:
                if remaining_to_match < self._min_match:
                    break
                take = min(axe.remaining, remaining_to_match)
                if take < self._min_match:
                    continue
                axe_remaining_before = axe.remaining
                axe.remaining -= take
                remaining_to_match -= take
                self._matched_total += take
                score = axe.confidence * (take / max(1, leg.quantity))
                suggestions.append(
                    MatchSuggestion(
                        axe_id=axe.axe_id,
                        client_id=axe.client_id,
                        symbol=axe.symbol,
                        axe_side=axe.side,
                        matched_quantity=take,
                        axe_remaining_before=axe_remaining_before,
                        confidence=round(axe.confidence, 4),
                        score=round(score, 4),
                        limit_price=axe.limit_price,
                    )
                )
                if axe.remaining == 0:
                    # Fully consumed — drop the axe and its refresh-index entry.
                    self._axes.pop(axe.axe_id, None)
                    self._refresh_index.pop((axe.client_id, axe.symbol, axe.side), None)
        return suggestions

    # -- read-only --------------------------------------------------------

    def snapshot(
        self, symbol: str | None = None, side: str | None = None
    ) -> list[dict[str, object]]:
        now = self._clock()
        with self._lock:
            self._expire_locked(now)
            rows: list[dict[str, object]] = []
            for axe in self._axes.values():
                if symbol is not None and axe.symbol != symbol:
                    continue
                if side is not None and axe.side != side:
                    continue
                ttl_remaining = max(0.0, axe.expires_at - now)
                ttl_total = max(1.0, axe.expires_at - axe.published_at)
                confidence = max(0.0, min(1.0, ttl_remaining / ttl_total))
                rows.append(
                    {
                        "axeId": axe.axe_id,
                        "clientId": axe.client_id,
                        "symbol": axe.symbol,
                        "side": axe.side,
                        "quantity": axe.quantity,
                        "remaining": axe.remaining,
                        "limitPrice": axe.limit_price,
                        "publishedAt": axe.published_at,
                        "expiresAt": axe.expires_at,
                        "confidence": round(confidence, 4),
                        "refreshCount": axe.refresh_count,
                    }
                )
            rows.sort(key=lambda r: -float(r["confidence"]))
            return rows

    def stats(self) -> dict[str, object]:
        with self._lock:
            return {
                "activeAxes": len(self._axes),
                "matchedTotalShares": self._matched_total,
            }

    # -- internals --------------------------------------------------------

    def _expire_locked(self, now: float) -> None:
        expired = [a.axe_id for a in self._axes.values() if a.expires_at <= now]
        for axe_id in expired:
            axe = self._axes.pop(axe_id, None)
            if axe is not None:
                self._refresh_index.pop((axe.client_id, axe.symbol, axe.side), None)
