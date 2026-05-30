"""PnL attribution — issue 2.2.5.

Decomposes the realized PnL of every completed parent order into five components, all in USD
notional (positive = profit for the desk, negative = cost):

- **spread** — the cost of crossing the bid/ask vs. the midpoint at the moment of execution.
  ``spread_usd = − sign(side) × (realized_avg − arrival_mid) × |qty|`` clamped to the
  spread-cost portion. The TCA service already computes ``spreadCostBps``; we re-frame it as
  a signed USD figure that adds across components.
- **timing** — the drift between the strategy's *decision* mid and the *arrival* mid. Positive
  if the market moved in our favour between signal and execution-start. Modelled as zero
  whenever a decision-mid isn't carried on the TCA row (MVP); placeholder for the regime
  classifier work in 2.3.x.
- **market** — the move on the underlying between the arrival mid and the VWAP benchmark
  ("market drift" during execution). Positive if the market moved with us.
- **commission** — fixed-bps haircut applied to traded notional; configurable via
  ``ANALYTICS_COMMISSION_BPS``. Always negative for paying liquidity.
- **residual** — the difference between the headline ``realized PnL = (vwap_bench − realized_avg)
  × signed_qty`` and the sum of the four above. Surfaces model error so dashboards don't quietly
  drift from accounting reality.

The four-piece decomposition mirrors the classical Kissell-Glantz framework used by sell-side
TCA desks; the inputs we have today (arrival mid, realized avg, vwap benchmark, side, qty) are
sufficient for spread, market, commission, and residual. Timing and hedging slots are reserved
for when 2.3.x adds the decision-time snapshot and 3.2.x adds hedging legs.
"""

from __future__ import annotations

import statistics
import threading
from collections import defaultdict
from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from datetime import date, datetime


@dataclass(slots=True, frozen=True)
class TcaInput:
    """Subset of the TCA payload the attribution math needs."""

    order_id: str
    strategy: str
    symbol: str
    side: str  # "BUY" or "SELL"
    quantity: float
    arrival_price: float
    realized_avg_price: float
    vwap_benchmark_price: float
    spread_cost_bps: float
    commission_total: float | None
    computed_at: datetime


@dataclass(slots=True, frozen=True)
class Attribution:
    """Per-order attribution row."""

    order_id: str
    strategy: str
    symbol: str
    side: str
    quantity: float
    spread_usd: float
    timing_usd: float
    market_usd: float
    commission_usd: float
    residual_usd: float
    realized_pnl_usd: float

    def total(self) -> float:
        return (
            self.spread_usd
            + self.timing_usd
            + self.market_usd
            + self.commission_usd
            + self.residual_usd
        )


class PnlAttributionEngine:
    """Computes per-order attribution and rolls it up by strategy / day.

    State lives in memory: a flat list of every attribution row plus per-strategy/per-day
    sums. The component is intentionally stateless across restarts — Kafka replay from
    ``analytics.tca`` rebuilds it.
    """

    def __init__(self, commission_bps: float = 0.5) -> None:
        self._commission_bps = commission_bps
        self._lock = threading.RLock()
        self._rows: list[Attribution] = []
        # (strategy, computed_at.date()) → component sum
        self._daily_totals: dict[tuple[str, date], dict[str, float]] = defaultdict(
            lambda: {
                "spread": 0.0,
                "timing": 0.0,
                "market": 0.0,
                "commission": 0.0,
                "residual": 0.0,
                "realized": 0.0,
                "orders": 0.0,
            }
        )

    def attribute(self, tca: TcaInput) -> Attribution:
        signed_qty = tca.quantity if tca.side == "BUY" else -tca.quantity
        # Spread cost is positive when we paid the bid/ask — by convention spread_cost_bps
        # is reported as positive, so we negate it into "−" PnL (a cost).
        spread_usd = -(tca.spread_cost_bps / 10_000.0) * tca.arrival_price * tca.quantity
        # Market drift between arrival and VWAP benchmark.
        market_usd = (tca.vwap_benchmark_price - tca.arrival_price) * signed_qty
        # Timing slot reserved; needs a decision-time mid the TCA row doesn't carry yet.
        timing_usd = 0.0
        # Commission: configurable bps haircut. Use the TCA-reported total when present;
        # otherwise estimate.
        if tca.commission_total is not None:
            commission_usd = -abs(tca.commission_total)
        else:
            commission_usd = (
                -(self._commission_bps / 10_000.0) * tca.realized_avg_price * tca.quantity
            )
        # Realized PnL relative to VWAP benchmark (the desk benchmark we're attributing to).
        realized_pnl_usd = (tca.vwap_benchmark_price - tca.realized_avg_price) * signed_qty
        residual_usd = realized_pnl_usd - (spread_usd + timing_usd + market_usd + commission_usd)

        row = Attribution(
            order_id=tca.order_id,
            strategy=tca.strategy,
            symbol=tca.symbol,
            side=tca.side,
            quantity=tca.quantity,
            spread_usd=round(spread_usd, 4),
            timing_usd=round(timing_usd, 4),
            market_usd=round(market_usd, 4),
            commission_usd=round(commission_usd, 4),
            residual_usd=round(residual_usd, 4),
            realized_pnl_usd=round(realized_pnl_usd, 4),
        )

        with self._lock:
            self._rows.append(row)
            key = (tca.strategy, tca.computed_at.date())
            totals = self._daily_totals[key]
            totals["spread"] += row.spread_usd
            totals["timing"] += row.timing_usd
            totals["market"] += row.market_usd
            totals["commission"] += row.commission_usd
            totals["residual"] += row.residual_usd
            totals["realized"] += row.realized_pnl_usd
            totals["orders"] += 1
        return row

    # -- snapshots --------------------------------------------------------

    def daily_summary(self, strategy: str | None = None) -> list[dict[str, object]]:
        """One row per (strategy, day) with summed component values."""
        with self._lock:
            rows: list[dict[str, object]] = []
            for (s, d), totals in self._daily_totals.items():
                if strategy is not None and s != strategy:
                    continue
                rows.append(
                    {
                        "strategy": s,
                        "date": d.isoformat(),
                        "orders": int(totals["orders"]),
                        "spreadUsd": round(totals["spread"], 4),
                        "timingUsd": round(totals["timing"], 4),
                        "marketUsd": round(totals["market"], 4),
                        "commissionUsd": round(totals["commission"], 4),
                        "residualUsd": round(totals["residual"], 4),
                        "realizedPnlUsd": round(totals["realized"], 4),
                    }
                )
            rows.sort(key=lambda r: (str(r["date"]), str(r["strategy"])))
            return rows

    def order_breakdown(self, order_id: str) -> dict[str, object] | None:
        with self._lock:
            for row in self._rows:
                if row.order_id == order_id:
                    return _row_dict(row)
        return None

    def strategy_distribution(self, strategy: str) -> dict[str, object]:
        """Per-component min/median/max/sum for a strategy. Useful for outlier hunts."""
        with self._lock:
            rows = [r for r in self._rows if r.strategy == strategy]
        if not rows:
            return {
                "strategy": strategy,
                "orders": 0,
                "components": {},
            }
        components = {
            "spread": [r.spread_usd for r in rows],
            "timing": [r.timing_usd for r in rows],
            "market": [r.market_usd for r in rows],
            "commission": [r.commission_usd for r in rows],
            "residual": [r.residual_usd for r in rows],
            "realized": [r.realized_pnl_usd for r in rows],
        }
        breakdown: dict[str, object] = {
            "strategy": strategy,
            "orders": len(rows),
            "components": {
                name: {
                    "sum": round(sum(values), 4),
                    "min": round(min(values), 4),
                    "median": round(statistics.median(values), 4),
                    "max": round(max(values), 4),
                }
                for name, values in components.items()
            },
        }
        return breakdown

    def all_rows(self) -> list[dict[str, object]]:
        with self._lock:
            return [_row_dict(r) for r in self._rows]


def _row_dict(row: Attribution) -> dict[str, object]:
    return {
        "orderId": row.order_id,
        "strategy": row.strategy,
        "symbol": row.symbol,
        "side": row.side,
        "quantity": row.quantity,
        "spreadUsd": row.spread_usd,
        "timingUsd": row.timing_usd,
        "marketUsd": row.market_usd,
        "commissionUsd": row.commission_usd,
        "residualUsd": row.residual_usd,
        "realizedPnlUsd": row.realized_pnl_usd,
    }
