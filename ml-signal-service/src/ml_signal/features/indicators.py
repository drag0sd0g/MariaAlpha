"""Technical indicator functions operating on NumPy arrays.

All functions accept a 1-D array and return a 1-D array of the same length.
Values before the indicator has enough data are filled with a neutral default.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

import numpy as np

if TYPE_CHECKING:
    from numpy.typing import NDArray


def ema(values: NDArray[np.float64], period: int) -> NDArray[np.float64]:
    """Exponential Moving Average.

    Seeds with the first value; alpha = 2 / (period + 1).
    """
    alpha = 2.0 / (period + 1)
    n = len(values)
    result = np.empty(n, dtype=np.float64)
    result[0] = values[0]
    for i in range(1, n):
        result[i] = alpha * values[i] + (1.0 - alpha) * result[i - 1]
    return result


def rsi(closes: NDArray[np.float64], period: int = 14) -> NDArray[np.float64]:
    """Relative Strength Index using Wilder's smoothing method.

    Returns values on the 0–100 scale. Positions before sufficient data
    are filled with 50.0 (neutral).
    """
    n = len(closes)
    result = np.full(n, 50.0, dtype=np.float64)

    if n < period + 1:
        return result

    deltas = np.diff(closes)
    gains = np.where(deltas > 0, deltas, 0.0)
    losses = np.where(deltas < 0, -deltas, 0.0)

    # First averages: SMA over the first `period` changes
    avg_gain = float(np.mean(gains[:period]))
    avg_loss = float(np.mean(losses[:period]))

    if avg_loss == 0:
        result[period] = 100.0
    else:
        rs = avg_gain / avg_loss
        result[period] = 100.0 - 100.0 / (1.0 + rs)

    # Wilder's smoothing for subsequent values
    for i in range(period, len(deltas)):
        avg_gain = (avg_gain * (period - 1) + gains[i]) / period
        avg_loss = (avg_loss * (period - 1) + losses[i]) / period

        if avg_loss == 0:
            result[i + 1] = 100.0
        else:
            rs = avg_gain / avg_loss
            result[i + 1] = 100.0 - 100.0 / (1.0 + rs)

    return result


def macd(
    closes: NDArray[np.float64],
    fast: int = 12,
    slow: int = 26,
    signal_period: int = 9,
) -> tuple[NDArray[np.float64], NDArray[np.float64], NDArray[np.float64]]:
    """MACD indicator.

    Returns (macd_line, signal_line, histogram).
    """
    fast_ema = ema(closes, fast)
    slow_ema = ema(closes, slow)
    macd_line = np.asarray(fast_ema - slow_ema, dtype=np.float64)
    signal_line = ema(macd_line, signal_period)
    histogram = np.asarray(macd_line - signal_line, dtype=np.float64)
    return macd_line, signal_line, histogram


def atr(
    highs: NDArray[np.float64],
    lows: NDArray[np.float64],
    closes: NDArray[np.float64],
    period: int = 14,
) -> NDArray[np.float64]:
    """Average True Range using Wilder's smoothing method.

    Positions before sufficient data are filled with 0.
    """
    n = len(closes)
    result = np.zeros(n, dtype=np.float64)

    if n < 2:
        return result

    # True Range: max(H-L, |H-prevC|, |L-prevC|)
    prev_close = np.roll(closes, 1)
    prev_close[0] = closes[0]
    tr = np.maximum(
        highs - lows,
        np.maximum(np.abs(highs - prev_close), np.abs(lows - prev_close)),
    )

    if n < period + 1:
        # Not enough data for full ATR — return simple average of available TR
        result[-1] = float(np.mean(tr[1:]))
        return result

    # First ATR: SMA of the first `period` true ranges (skip index 0)
    result[period] = float(np.mean(tr[1 : period + 1]))

    # Wilder's smoothing
    for i in range(period + 1, n):
        result[i] = (result[i - 1] * (period - 1) + tr[i]) / period

    return result
