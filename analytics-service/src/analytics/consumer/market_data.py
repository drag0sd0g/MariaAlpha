"""Market-data consumer — keeps a last-trade-price cache per symbol."""

from __future__ import annotations

import json
import threading
import time
from typing import TYPE_CHECKING

import structlog
from confluent_kafka import Consumer, KafkaError

if TYPE_CHECKING:
    from analytics.config import Settings

logger = structlog.get_logger()


class MarketDataCache:
    """Bounded ring of recent ticks per symbol; supports ``price_at(symbol, ts)`` lookup."""

    def __init__(self, max_history_per_symbol: int = 4096) -> None:
        self._max = max_history_per_symbol
        self._lock = threading.RLock()
        self._history: dict[str, list[tuple[float, float]]] = {}

    def record(self, symbol: str, ts_seconds: float, price: float) -> None:
        with self._lock:
            history = self._history.setdefault(symbol, [])
            if history and ts_seconds < history[-1][0]:
                import bisect

                idx = bisect.bisect_left(history, (ts_seconds, price))
                history.insert(idx, (ts_seconds, price))
            else:
                history.append((ts_seconds, price))
            if len(history) > self._max:
                trim = self._max // 4
                self._history[symbol] = history[trim:]

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
            import bisect

            idx = bisect.bisect_right(history, (ts_seconds, float("inf"))) - 1
            if idx < 0:
                return None
            return history[idx][1]


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
                ts_seconds = _iso_to_epoch(ts) if isinstance(ts, str) else time.time()
                self._cache.record(symbol, ts_seconds, float(price))
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
