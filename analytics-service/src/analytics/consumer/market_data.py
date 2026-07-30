"""Market-data consumer — keeps a last-trade-price cache per symbol."""

from __future__ import annotations

import bisect
import json
import math
import threading
import time
from typing import TYPE_CHECKING, Literal

import numpy as np
import structlog
from confluent_kafka import Consumer, KafkaError

if TYPE_CHECKING:
    from collections.abc import Sequence

    from analytics.config import Settings
    from analytics.numeric import FloatArray

logger = structlog.get_logger()


class MarketDataCache:
    """Bounded ring of recent ticks per symbol; supports ``price_at(symbol, ts)`` lookup.

    Two parallel time axes are kept per symbol:

    - ``_history`` — keyed by the tick's **event** timestamp. This is what the toxicity detector
      measures markout against, since a markout horizon is a statement about market time.
    - ``_arrival`` — keyed by the wall-clock time the tick was ingested. Needed by the covariance
      estimator (roadmap 4.6.1) because the simulated market-data tape loops a fixed CSV without
      rewriting timestamps, so the event axis repeats forever and yields a degenerate return
      series. On a real feed the two axes agree up to ingest latency.
    """

    def __init__(self, max_history_per_symbol: int = 4096) -> None:
        self._max = max_history_per_symbol
        self._lock = threading.RLock()
        self._history: dict[str, list[tuple[float, float]]] = {}
        self._arrival: dict[str, list[tuple[float, float]]] = {}

    def record(
        self, symbol: str, ts_seconds: float, price: float, arrival_seconds: float | None = None
    ) -> None:
        arrival = time.time() if arrival_seconds is None else arrival_seconds
        with self._lock:
            history = self._history.setdefault(symbol, [])
            if history and ts_seconds < history[-1][0]:
                idx = bisect.bisect_left(history, (ts_seconds, price))
                history.insert(idx, (ts_seconds, price))
            else:
                history.append((ts_seconds, price))
            if len(history) > self._max:
                trim = self._max // 4
                self._history[symbol] = history[trim:]

            # The arrival axis is monotonic by construction, so a plain append is enough.
            arrivals = self._arrival.setdefault(symbol, [])
            arrivals.append((arrival, price))
            if len(arrivals) > self._max:
                trim = self._max // 4
                self._arrival[symbol] = arrivals[trim:]

    def latest(self, symbol: str) -> float | None:
        with self._lock:
            history = self._history.get(symbol)
            return history[-1][1] if history else None

    def price_at(self, symbol: str, ts_seconds: float) -> float | None:
        """Last recorded price at or before ``ts_seconds``.

        Used by the toxicity detector to evaluate markout at a horizon. Returns ``None``
        when the cache has no observation for the symbol yet.
        """
        with self._lock:
            history = self._history.get(symbol)
            if not history:
                return None
            idx = bisect.bisect_right(history, (ts_seconds, float("inf"))) - 1
            if idx < 0:
                return None
            return history[idx][1]

    def tracked_symbols(self) -> tuple[str, ...]:
        with self._lock:
            return tuple(sorted(self._history))

    def bars(
        self,
        symbol: str,
        bar_seconds: int,
        clock: Literal["arrival", "event"] = "arrival",
    ) -> dict[int, float]:
        """Last price in each ``bar_seconds`` bucket, keyed by bucket index.

        Bucketing on the *last* price in the interval (rather than the mean) keeps the series a
        genuine close-to-close sequence, which is what log returns assume.
        """
        if bar_seconds <= 0:
            raise ValueError("bar_seconds must be positive")
        with self._lock:
            source = self._arrival if clock == "arrival" else self._history
            history = list(source.get(symbol, ()))
        buckets: dict[int, float] = {}
        for ts, price in history:
            if price > 0 and math.isfinite(price):
                buckets[int(ts // bar_seconds)] = price
        return buckets

    def returns_matrix(
        self,
        symbols: Sequence[str],
        bar_seconds: int,
        max_bars: int = 1_440,
        clock: Literal["arrival", "event"] = "arrival",
    ) -> tuple[FloatArray, tuple[str, ...]]:
        """Aligned log-return matrix ``(T x N)`` for the symbols that have usable history.

        Symbols tick at different times, so the buckets are aligned on the union of all bucket
        indices from the point where *every* included symbol has at least one observation, with
        forward-fill inside each series. Aligning on the intersection instead would usually
        return nothing; aligning without a common start would treat a symbol's pre-history as
        flat and manufacture a spurious zero-vol, zero-correlation block.

        Returns an empty ``(0, 0)`` matrix and an empty symbol tuple when fewer than two buckets
        are shared — callers fall back to the configured prior.
        """
        series = {s: self.bars(s, bar_seconds, clock) for s in symbols}
        usable = tuple(s for s in symbols if len(series[s]) >= 2)
        if len(usable) == 0:
            return np.zeros((0, 0), dtype=np.float64), ()

        start = max(min(series[s]) for s in usable)
        union = sorted({b for s in usable for b in series[s] if b >= start})
        if len(union) < 2:
            return np.zeros((0, 0), dtype=np.float64), ()
        union = union[-(max_bars + 1) :]

        levels = np.empty((len(union), len(usable)), dtype=np.float64)
        for col, symbol in enumerate(usable):
            buckets = series[symbol]
            keys = sorted(buckets)
            last = buckets[keys[0]]
            for row, bucket in enumerate(union):
                idx = bisect.bisect_right(keys, bucket) - 1
                if idx >= 0:
                    last = buckets[keys[idx]]
                levels[row, col] = last

        returns = np.diff(np.log(levels), axis=0)
        returns = returns[np.isfinite(returns).all(axis=1)]
        return returns, usable


class MarketDataConsumer:
    """Polls ``market-data.ticks`` and feeds the cache."""

    def __init__(self, settings: Settings, cache: MarketDataCache) -> None:
        self._consumer = Consumer(
            {
                "bootstrap.servers": settings.kafka_bootstrap_servers,
                "group.id": f"{settings.kafka_consumer_group}-market-data",
                "auto.offset.reset": "latest",
                "enable.auto.commit": True,
            }
        )
        self._topic = settings.kafka_market_data_topic
        self._cache = cache
        self._running = True

    def run(self) -> None:
        self._consumer.subscribe([self._topic])
        logger.info("market_data_consumer_started", topic=self._topic)
        while self._running:
            msg = self._consumer.poll(timeout=1.0)
            if msg is None:
                continue
            err = msg.error()
            if err is not None:
                if err.code() == KafkaError._PARTITION_EOF:
                    continue
                logger.error("market_data_consumer_error", error=str(err))
                continue
            try:
                value = msg.value()
                if value is None:
                    continue
                tick = json.loads(value.decode("utf-8"))
                price = tick.get("price")
                symbol = tick.get("symbol")
                ts = tick.get("timestamp")
                if price is None or symbol is None or float(price) <= 0:
                    continue
                arrival = time.time()
                ts_seconds = _iso_to_epoch(ts) if isinstance(ts, str) else arrival
                self._cache.record(symbol, ts_seconds, float(price), arrival_seconds=arrival)
            except Exception:
                logger.exception("market_data_tick_processing_failed")

    def stop(self) -> None:
        self._running = False
        self._consumer.close()


def _iso_to_epoch(iso: str) -> float:
    from datetime import datetime

    try:
        if iso.endswith("Z"):
            iso = iso[:-1] + "+00:00"
        return datetime.fromisoformat(iso).timestamp()
    except ValueError:
        return time.time()
