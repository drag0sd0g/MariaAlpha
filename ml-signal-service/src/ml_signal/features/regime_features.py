"""Regime classifier features.

Eight statistical features (per TDD §5.2.3) computed from a rolling window of
completed OHLCV bars. The feature set is designed to discriminate between five
market regimes — TRENDING_UP, TRENDING_DOWN, MEAN_REVERTING, HIGH_VOLATILITY,
LOW_VOLATILITY — without being sensitive to the absolute price scale.

The features are scale-invariant by construction (slopes are normalised, vols
are ratios), so the same model trained on synthetic minute bars generalises to
real intraday data without re-scaling.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

import numpy as np

if TYPE_CHECKING:
    from numpy.typing import NDArray

    from ml_signal.features.engine import Bar


REGIME_FEATURE_NAMES: list[str] = [
    "trend_strength",
    "trend_r_squared",
    "volatility_ratio",
    "realized_vol",
    "mean_reversion_score",
    "return_dispersion",
    "directional_strength",
    "price_distance_from_mean",
]


MIN_BARS_FOR_REGIME: int = 60


def compute_regime_features(bars: list[Bar]) -> dict[str, float] | None:
    """Compute the 8 regime features from a list of completed bars.

    Returns None if the window is too short. The output dict always contains
    every key in REGIME_FEATURE_NAMES — never a partial vector — so downstream
    callers can build feature arrays without defensive `.get(name, 0.0)` calls.
    """
    if len(bars) < MIN_BARS_FOR_REGIME:
        return None

    closes = np.asarray([b.close for b in bars], dtype=np.float64)
    return compute_regime_features_from_closes(closes)


def compute_regime_features_from_closes(
    closes: NDArray[np.float64],
) -> dict[str, float] | None:
    """Compute regime features from a close-price array only.

    Exposed separately so the training script (which works on pure 1-D price
    series, not Bar objects) can share the exact same feature implementation
    as the live engine. Drift between training-time and inference-time features
    is the most common cause of silent model regressions.
    """
    if len(closes) < MIN_BARS_FOR_REGIME:
        return None

    log_closes = np.log(np.maximum(closes, 1e-12))
    returns = np.diff(log_closes)

    trend_strength, trend_r2 = _trend_strength_and_r2(log_closes)
    short_vol = float(np.std(returns[-20:])) if len(returns) >= 20 else float(np.std(returns))
    long_vol = float(np.std(returns))
    vol_ratio = short_vol / long_vol if long_vol > 1e-12 else 1.0

    mean_reversion_score = -_lag1_autocorrelation(returns)

    return_dispersion = float(np.quantile(returns, 0.75) - np.quantile(returns, 0.25))

    abs_mean = float(np.mean(np.abs(returns)))
    directional_strength = abs_mean / long_vol if long_vol > 1e-12 else 0.0

    sma = float(np.mean(closes))
    price_distance = (float(closes[-1]) - sma) / sma if sma > 1e-12 else 0.0

    return {
        "trend_strength": trend_strength,
        "trend_r_squared": trend_r2,
        "volatility_ratio": vol_ratio,
        "realized_vol": long_vol,
        "mean_reversion_score": mean_reversion_score,
        "return_dispersion": return_dispersion,
        "directional_strength": directional_strength,
        "price_distance_from_mean": price_distance,
    }


def _trend_strength_and_r2(log_closes: NDArray[np.float64]) -> tuple[float, float]:
    """OLS regression of log price on time → (slope, R²).

    Slope is reported per-bar (not annualised) and represents fractional growth
    per bar. R² indicates how well the linear trend explains the price path:
    high R² + non-zero slope = clean trend; low R² = noisy/choppy.
    """
    n = len(log_closes)
    x = np.arange(n, dtype=np.float64)
    x_mean = float(np.mean(x))
    y_mean = float(np.mean(log_closes))

    x_centered = x - x_mean
    y_centered = log_closes - y_mean

    denom = float(np.dot(x_centered, x_centered))
    if denom < 1e-12:
        return 0.0, 0.0

    slope = float(np.dot(x_centered, y_centered) / denom)

    ss_total = float(np.dot(y_centered, y_centered))
    if ss_total < 1e-12:
        return 0.0, 1.0

    y_pred = y_mean + slope * x_centered
    ss_residual = float(np.sum((log_closes - y_pred) ** 2))
    r_squared = max(0.0, 1.0 - ss_residual / ss_total)

    return slope, r_squared


def _lag1_autocorrelation(values: NDArray[np.float64]) -> float:
    """Pearson autocorrelation at lag 1.

    Returns 0.0 if there is insufficient variance to compute it.
    """
    if len(values) < 3:
        return 0.0
    a = values[:-1]
    b = values[1:]
    a_centered = a - np.mean(a)
    b_centered = b - np.mean(b)
    denom = float(np.sqrt(np.sum(a_centered**2) * np.sum(b_centered**2)))
    if denom < 1e-12:
        return 0.0
    return float(np.sum(a_centered * b_centered) / denom)
