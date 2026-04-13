"""Feature computation engine.

Aggregates ticks into 1-minute OHLCV bars and computes 15 technical indicator
features per symbol.
"""

from __future__ import annotations

import contextlib
import queue
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from typing import TYPE_CHECKING

import numpy as np
import structlog

from ml_signal.features import indicators
from ml_signal.metrics import FEATURES_COMPUTED, TICKS_CONSUMED

if TYPE_CHECKING:
    from ml_signal.config import Settings

logger = structlog.get_logger()

FEATURE_NAMES: list[str] = [
    "ema_20",
    "ema_50",
    "ema_cross",
    "ema_20_dist",
    "ema_50_dist",
    "rsi_14",
    "macd_line",
    "macd_signal",
    "macd_hist",
    "atr_14",
    "atr_norm",
    "volume_ratio",
    "realized_vol",
    "return_1",
    "return_5",
]


@dataclass
class Bar:
    """A completed 1-minute OHLCV bar."""

    timestamp: float  # epoch seconds of bar start
    open: float
    high: float
    low: float
    close: float
    volume: int


@dataclass
class BarBuilder:
    """Accumulates ticks within a single bar interval."""

    timestamp: float
    open: float
    high: float
    low: float
    close: float
    volume: int

    def update(self, price: float, vol: int) -> None:
        if price > self.high:
            self.high = price
        if price < self.low:
            self.low = price
        self.close = price
        self.volume += vol

    def to_bar(self) -> Bar:
        return Bar(self.timestamp, self.open, self.high, self.low, self.close, self.volume)


class FeatureEngine:
    """Aggregates market ticks into bars and computes technical indicator features."""

    def __init__(self, settings: Settings) -> None:
        self._bar_interval = settings.bar_interval_seconds
        self._min_bars = settings.min_bars_for_features
        self._max_bars = settings.max_bars_retained

        # Per-symbol state (protected by _lock)
        self._bars: dict[str, list[Bar]] = {}
        self._current_bar: dict[str, BarBuilder] = {}
        self._features: dict[str, dict[str, float]] = {}
        self._last_update: dict[str, float] = {}

        self._lock = threading.Lock()

        # StreamSignals listeners: list of (queue, symbol_filter)
        # symbol_filter is None for "all symbols"
        self._listeners: list[
            tuple[queue.Queue[tuple[str, dict[str, float]]], set[str] | None]
        ] = []

    def on_tick(self, tick: dict[str, object]) -> None:
        """Process a raw tick dict from Kafka."""
        symbol = str(tick.get("symbol", ""))
        event_type = str(tick.get("eventType", ""))
        if not symbol:
            return

        TICKS_CONSUMED.labels(symbol=symbol, event_type=event_type).inc()

        extracted = self._extract_price_and_volume(tick)
        if extracted is None:
            return
        price, vol = extracted

        ts = self._parse_timestamp(tick)
        if ts is None:
            return

        bar_ts = (ts // self._bar_interval) * self._bar_interval

        with self._lock:
            if symbol not in self._current_bar:
                self._current_bar[symbol] = BarBuilder(bar_ts, price, price, price, price, vol)
                return

            current = self._current_bar[symbol]

            if bar_ts == current.timestamp:
                # Same bar — accumulate
                current.update(price, vol)
            else:
                # Bar completed — finalize and start new
                completed = current.to_bar()
                bars = self._bars.setdefault(symbol, [])
                bars.append(completed)
                if len(bars) > self._max_bars:
                    bars.pop(0)

                self._current_bar[symbol] = BarBuilder(bar_ts, price, price, price, price, vol)

                # Recompute features if we have enough bars
                if len(bars) >= self._min_bars:
                    features = self._compute_features(bars)
                    self._features[symbol] = features
                    self._last_update[symbol] = time.time()
                    FEATURES_COMPUTED.labels(symbol=symbol).inc()
                    logger.debug("features_computed", symbol=symbol, n_bars=len(bars))
                    self._notify_listeners(symbol, features)

    def get_features(self, symbol: str) -> dict[str, float] | None:
        """Thread-safe read of the latest feature vector for a symbol."""
        with self._lock:
            return self._features.get(symbol)

    def symbols_with_features(self) -> list[str]:
        """Return symbols that have feature vectors available."""
        with self._lock:
            return list(self._features.keys())

    def last_update_times(self) -> dict[str, float]:
        """Return {symbol: epoch_time} of last feature update."""
        with self._lock:
            return dict(self._last_update)

    def add_listener(
        self,
        q: queue.Queue[tuple[str, dict[str, float]]],
        symbols: set[str] | None = None,
    ) -> None:
        """Register a queue for StreamSignals notifications."""
        with self._lock:
            self._listeners.append((q, symbols))

    def remove_listener(self, q: queue.Queue[tuple[str, dict[str, float]]]) -> None:
        """Unregister a StreamSignals queue."""
        with self._lock:
            self._listeners = [(lq, ls) for lq, ls in self._listeners if lq is not q]

    # --- private ---

    def _notify_listeners(self, symbol: str, features: dict[str, float]) -> None:
        for q, sym_filter in self._listeners:
            if sym_filter is None or symbol in sym_filter:
                with contextlib.suppress(queue.Full):
                    q.put_nowait((symbol, features))

    @staticmethod
    def _extract_price_and_volume(tick: dict[str, object]) -> tuple[float, int] | None:
        event_type = str(tick.get("eventType", ""))
        if event_type == "TRADE":
            price = float(tick.get("tradePrice", 0))  # type: ignore[arg-type]
            vol = int(tick.get("tradeVolume", 0))  # type: ignore[call-overload]
            if price > 0:
                return price, vol
        elif event_type == "QUOTE":
            bid = float(tick.get("bidPrice", 0))  # type: ignore[arg-type]
            ask = float(tick.get("askPrice", 0))  # type: ignore[arg-type]
            if bid > 0 and ask > 0:
                return (bid + ask) / 2.0, 0
        return None

    @staticmethod
    def _parse_timestamp(tick: dict[str, object]) -> float | None:
        ts_str = tick.get("timestamp")
        if not isinstance(ts_str, str):
            return None
        try:
            dt = datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
            return dt.timestamp()
        except ValueError:
            return None

    @staticmethod
    def _compute_features(bars: list[Bar]) -> dict[str, float]:
        closes = np.array([b.close for b in bars], dtype=np.float64)
        highs = np.array([b.high for b in bars], dtype=np.float64)
        lows = np.array([b.low for b in bars], dtype=np.float64)
        volumes = np.array([b.volume for b in bars], dtype=np.float64)

        ema_20_arr = indicators.ema(closes, 20)
        ema_50_arr = indicators.ema(closes, 50)
        rsi_arr = indicators.rsi(closes, 14)
        macd_line, macd_sig, macd_hist = indicators.macd(closes)
        atr_arr = indicators.atr(highs, lows, closes, 14)

        last_close = closes[-1]

        # Volume ratio
        vol_window = volumes[-20:] if len(volumes) >= 20 else volumes
        vol_sma = float(np.mean(vol_window))
        vol_ratio = float(volumes[-1]) / vol_sma if vol_sma > 0 else 1.0

        # Realized volatility (std of 1-bar returns, 20 periods)
        if len(closes) >= 2:
            returns = np.diff(closes) / closes[:-1]
            ret_window = returns[-20:] if len(returns) >= 20 else returns
            realized_vol = float(np.std(ret_window))
        else:
            realized_vol = 0.0

        # Returns
        return_1 = float(closes[-1] / closes[-2] - 1) if len(closes) >= 2 else 0.0
        return_5 = float(closes[-1] / closes[-6] - 1) if len(closes) >= 6 else 0.0

        ema20 = float(ema_20_arr[-1])
        ema50 = float(ema_50_arr[-1])

        return {
            "ema_20": ema20,
            "ema_50": ema50,
            "ema_cross": ema20 - ema50,
            "ema_20_dist": (last_close - ema20) / ema20 if ema20 != 0 else 0.0,
            "ema_50_dist": (last_close - ema50) / ema50 if ema50 != 0 else 0.0,
            "rsi_14": float(rsi_arr[-1]),
            "macd_line": float(macd_line[-1]),
            "macd_signal": float(macd_sig[-1]),
            "macd_hist": float(macd_hist[-1]),
            "atr_14": float(atr_arr[-1]),
            "atr_norm": float(atr_arr[-1]) / last_close if last_close != 0 else 0.0,
            "volume_ratio": vol_ratio,
            "realized_vol": realized_vol,
            "return_1": return_1,
            "return_5": return_5,
        }
