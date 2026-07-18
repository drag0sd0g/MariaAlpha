"""Flow toxicity detector.

Computes per-strategy *markout*: the signed price move on the symbol over a fixed horizon
after a fill, expressed in basis points and oriented so that **positive = adverse selection**
(the price moved against the desk after the fill, suggesting we were picked off).

The desk's PnL convention is:

- A BUY at price ``P`` is adversely selected if the price *falls* after the fill — we paid up
  for shares that quickly traded lower. ``markout_bps = (P − P_after) / P × 10_000``.
- A SELL at price ``P`` is adversely selected if the price *rises* after the fill — we let go
  of shares the market then bid up. ``markout_bps = (P_after − P) / P × 10_000``.

Both branches share the formula ``sign(side) × (P_after − P) / P × 10_000`` with
``sign(SELL) = +1`` and ``sign(BUY) = −1``.

Toxicity is a property of *flow*, not a single fill. We aggregate per ``strategy`` (a proxy for
counterparty in the MVP, since real client tags don't yet flow through the pipeline). When a
strategy's rolling mean markout breaches ``toxicity_threshold_bps`` at any configured horizon,
the detector publishes a ``RISK_ALERT`` on ``analytics.risk-alerts``.

The component is intentionally state-light: a bounded per-strategy ring of markout samples per
horizon, plus a snapshot endpoint. Persistence is out of scope — restart drops the rolling
window, and aggregates are rebuilt from replay.
"""

from __future__ import annotations

import statistics
import threading
from collections import deque
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import TYPE_CHECKING

import structlog

from analytics.metrics import TOXICITY_ALERTS, TOXICITY_MARKOUT_BPS

if TYPE_CHECKING:
    from collections.abc import Callable, Iterable

logger = structlog.get_logger()


@dataclass(slots=True)
class FillRecord:
    """A single fill observation pending markout measurement."""

    order_id: str
    strategy: str
    symbol: str
    side: str
    fill_price: float
    fill_ts_seconds: float


@dataclass(slots=True)
class StrategyStats:
    """Rolling toxicity stats for one strategy at one horizon."""

    samples: deque[float] = field(default_factory=lambda: deque(maxlen=512))

    def add(self, markout_bps: float) -> None:
        self.samples.append(markout_bps)

    def mean(self) -> float:
        return statistics.mean(self.samples) if self.samples else 0.0

    def stdev(self) -> float:
        return statistics.stdev(self.samples) if len(self.samples) > 1 else 0.0

    def count(self) -> int:
        return len(self.samples)


class FlowToxicityDetector:
    """In-memory toxicity detector.

    Wire the ``on_fill`` callback to your ``analytics.tca`` consumer and the
    ``measure_horizon`` callback to whatever provides ``price(symbol, ts)``. The detector
    schedules the horizon measurement itself via a monotonic clock and triggers alerts when
    thresholds breach.
    """

    def __init__(
        self,
        horizons_seconds: tuple[int, ...],
        threshold_bps: float,
        min_observations: int,
        price_lookup: Callable[[str, float], float | None],
        alert_publisher: Callable[[dict[str, object]], None] | None = None,
        clock: Callable[[], float] = None,  # type: ignore[assignment]
    ) -> None:
        if not horizons_seconds:
            raise ValueError("horizons_seconds must be non-empty")
        if threshold_bps <= 0:
            raise ValueError("threshold_bps must be positive")
        self._horizons = horizons_seconds
        self._threshold = threshold_bps
        self._min_obs = min_observations
        self._price_lookup = price_lookup
        self._alert_publisher = alert_publisher
        if clock is None:
            import time

            clock = time.monotonic
        self._clock = clock
        self._lock = threading.RLock()
        self._pending: dict[int, list[FillRecord]] = {h: [] for h in horizons_seconds}
        self._stats: dict[tuple[str, int], StrategyStats] = {}
        self._last_alert_ts: dict[tuple[str, int], float] = {}
        self._alert_cooldown_seconds = 60.0

    def on_fill(self, fill: FillRecord) -> None:
        """Register a fill for later markout measurement at each horizon."""
        with self._lock:
            for horizon in self._horizons:
                self._pending[horizon].append(fill)

    def tick(self, now: float | None = None) -> int:
        """Process any pending fills whose horizon has elapsed.

        Returns the number of markouts computed in this tick. Call this from a background
        timer or directly from tests with a fake clock.
        """
        if now is None:
            now = self._clock()
        observations = 0
        with self._lock:
            for horizon in self._horizons:
                pending = self._pending[horizon]
                drained: list[FillRecord] = []
                remaining: list[FillRecord] = []
                for fill in pending:
                    if fill.fill_ts_seconds + horizon <= now:
                        drained.append(fill)
                    else:
                        remaining.append(fill)
                self._pending[horizon] = remaining
                for fill in drained:
                    price_after = self._price_lookup(fill.symbol, fill.fill_ts_seconds + horizon)
                    if price_after is None or fill.fill_price <= 0:
                        continue
                    sign = 1.0 if fill.side == "SELL" else -1.0
                    markout_bps = (
                        sign * (price_after - fill.fill_price) / fill.fill_price * 10_000.0
                    )
                    self._record(fill.strategy, horizon, markout_bps, now)
                    observations += 1
        return observations

    def _record(self, strategy: str, horizon: int, markout_bps: float, now: float) -> None:
        key = (strategy, horizon)
        stats = self._stats.setdefault(key, StrategyStats())
        stats.add(markout_bps)
        TOXICITY_MARKOUT_BPS.labels(strategy=strategy, horizon_seconds=str(horizon)).observe(
            markout_bps
        )
        if stats.count() < self._min_obs:
            return
        if stats.mean() < self._threshold:
            return
        last = self._last_alert_ts.get(key, -1e18)
        if now - last < self._alert_cooldown_seconds:
            return
        self._last_alert_ts[key] = now
        if self._alert_publisher is not None:
            mean_markout = stats.mean()
            alert = {
                "alertType": "FLOW_TOXICITY",
                # `symbol` and `message` complete the RiskAlert contract the UI/exec-engine
                # producers share; toxicity is strategy-scoped, so the strategy stands in for
                # the symbol slot. `timestamp` is a wall-clock ISO-8601 instant (the `now`
                # argument is a monotonic clock, unusable as a calendar time) — the UI parses
                # it with `new Date(...)`, so an omitted field rendered as "Invalid Date".
                "symbol": strategy,
                "message": (
                    f"Toxic flow on {strategy}: mean markout {mean_markout:.1f}bps "
                    f"over {horizon}s (threshold {self._threshold:.0f}bps, "
                    f"n={stats.count()})"
                ),
                "timestamp": datetime.now(tz=UTC).isoformat(),
                "strategy": strategy,
                "horizonSeconds": horizon,
                "meanMarkoutBps": round(mean_markout, 4),
                "stdevMarkoutBps": round(stats.stdev(), 4),
                "observations": stats.count(),
                "thresholdBps": self._threshold,
                "severity": "WARNING",
            }
            try:
                self._alert_publisher(alert)
                TOXICITY_ALERTS.labels(strategy=strategy, horizon_seconds=str(horizon)).inc()
            except Exception:
                logger.exception("toxicity_alert_publish_failed", strategy=strategy)

    def snapshot(self, strategy: str | None = None) -> list[dict[str, object]]:
        """Return per-(strategy, horizon) toxicity rows, sorted by mean markout descending."""
        with self._lock:
            keys: Iterable[tuple[str, int]]
            keys = (
                ((s, h) for (s, h) in self._stats if s == strategy)
                if strategy is not None
                else self._stats.keys()
            )
            rows: list[dict[str, object]] = []
            for s, h in keys:
                stats = self._stats[(s, h)]
                rows.append(
                    {
                        "strategy": s,
                        "horizonSeconds": h,
                        "meanMarkoutBps": round(stats.mean(), 4),
                        "stdevMarkoutBps": round(stats.stdev(), 4),
                        "observations": stats.count(),
                        "toxic": stats.count() >= self._min_obs and stats.mean() >= self._threshold,
                    }
                )
            rows.sort(key=lambda r: -float(r["meanMarkoutBps"]))  # type: ignore[arg-type]
            return rows

    def pending_count(self) -> int:
        with self._lock:
            return sum(len(p) for p in self._pending.values())
