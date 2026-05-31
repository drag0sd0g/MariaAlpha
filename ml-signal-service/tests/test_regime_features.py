"""Unit tests for the regime classifier feature extractor."""

from __future__ import annotations

import numpy as np
import pytest

from ml_signal.features.engine import Bar
from ml_signal.features.regime_features import (
    MIN_BARS_FOR_REGIME,
    REGIME_FEATURE_NAMES,
    compute_regime_features,
    compute_regime_features_from_closes,
)


def _make_bars(closes: list[float]) -> list[Bar]:
    """Wrap a close-price series in dummy Bar objects (one bar per close)."""
    return [
        Bar(timestamp=float(i * 60), open=c, high=c, low=c, close=c, volume=1000)
        for i, c in enumerate(closes)
    ]


class TestRegimeFeaturesWindow:
    def test_returns_none_below_min_bars(self) -> None:
        closes = np.linspace(100.0, 110.0, MIN_BARS_FOR_REGIME - 1)
        assert compute_regime_features_from_closes(closes) is None

    def test_returns_none_for_empty_bar_list(self) -> None:
        assert compute_regime_features([]) is None

    def test_returns_full_dict_at_min_bars(self) -> None:
        closes = np.linspace(100.0, 110.0, MIN_BARS_FOR_REGIME)
        result = compute_regime_features_from_closes(closes)
        assert result is not None
        assert set(result.keys()) == set(REGIME_FEATURE_NAMES)
        for name, value in result.items():
            assert np.isfinite(value), f"{name} = {value}"


class TestTrendingUp:
    """A clean linear uptrend should produce positive trend_strength and high R²."""

    def test_uptrend_signature(self) -> None:
        rng = np.random.default_rng(0)
        # Strong drift + tiny noise → clean trend
        log_returns = rng.normal(loc=0.002, scale=0.0005, size=MIN_BARS_FOR_REGIME - 1)
        closes = 100.0 * np.exp(np.concatenate([[0.0], np.cumsum(log_returns)]))
        features = compute_regime_features_from_closes(closes)
        assert features is not None
        assert features["trend_strength"] > 0.001
        assert features["trend_r_squared"] > 0.8
        assert features["price_distance_from_mean"] > 0.0  # ended above mean


class TestTrendingDown:
    def test_downtrend_signature(self) -> None:
        rng = np.random.default_rng(1)
        log_returns = rng.normal(loc=-0.002, scale=0.0005, size=MIN_BARS_FOR_REGIME - 1)
        closes = 100.0 * np.exp(np.concatenate([[0.0], np.cumsum(log_returns)]))
        features = compute_regime_features_from_closes(closes)
        assert features is not None
        assert features["trend_strength"] < -0.001
        assert features["trend_r_squared"] > 0.8
        assert features["price_distance_from_mean"] < 0.0  # ended below mean


class TestMeanReverting:
    """AR(1) returns with negative coefficient should yield a positive mean_reversion_score."""

    def test_negative_ar1_yields_positive_score(self) -> None:
        rng = np.random.default_rng(2)
        n = MIN_BARS_FOR_REGIME
        log_returns = np.zeros(n - 1)
        prev = 0.0
        for i in range(n - 1):
            innovation = rng.normal(loc=0.0, scale=0.002)
            log_returns[i] = -0.5 * prev + innovation
            prev = log_returns[i]
        closes = 100.0 * np.exp(np.concatenate([[0.0], np.cumsum(log_returns)]))
        features = compute_regime_features_from_closes(closes)
        assert features is not None
        # The mean-reversion score is -AR(1); a negative AR(1) ⇒ positive score.
        assert features["mean_reversion_score"] > 0.2
        # The trend is weak → low R²
        assert features["trend_r_squared"] < 0.5


class TestHighVsLowVolatility:
    def test_high_vol_has_larger_realized_vol(self) -> None:
        rng = np.random.default_rng(3)
        n = MIN_BARS_FOR_REGIME
        high_vol_returns = rng.normal(loc=0.0, scale=0.02, size=n - 1)
        low_vol_returns = rng.normal(loc=0.0, scale=0.0005, size=n - 1)
        high_closes = 100.0 * np.exp(np.concatenate([[0.0], np.cumsum(high_vol_returns)]))
        low_closes = 100.0 * np.exp(np.concatenate([[0.0], np.cumsum(low_vol_returns)]))

        high = compute_regime_features_from_closes(high_closes)
        low = compute_regime_features_from_closes(low_closes)
        assert high is not None and low is not None
        assert high["realized_vol"] > 10 * low["realized_vol"]
        assert high["return_dispersion"] > low["return_dispersion"]


class TestNumericalEdgeCases:
    def test_constant_prices_produce_finite_features(self) -> None:
        """A constant-price window is degenerate but must not blow up."""
        closes = np.full(MIN_BARS_FOR_REGIME, 100.0)
        features = compute_regime_features_from_closes(closes)
        assert features is not None
        for name, value in features.items():
            assert np.isfinite(value), f"{name} = {value}"
        # Constant series: no trend, no vol, no reversion signal
        assert features["trend_strength"] == pytest.approx(0.0, abs=1e-9)
        assert features["realized_vol"] == pytest.approx(0.0, abs=1e-9)
        assert features["return_dispersion"] == pytest.approx(0.0, abs=1e-9)

    def test_scale_invariance_of_normalised_features(self) -> None:
        """Multiplying the price series by a constant should not change vol/trend ratios."""
        rng = np.random.default_rng(4)
        log_returns = rng.normal(loc=0.0005, scale=0.002, size=MIN_BARS_FOR_REGIME - 1)
        closes = 50.0 * np.exp(np.concatenate([[0.0], np.cumsum(log_returns)]))

        a = compute_regime_features_from_closes(closes)
        b = compute_regime_features_from_closes(closes * 1000.0)
        assert a is not None and b is not None
        for name in [
            "trend_strength",
            "trend_r_squared",
            "volatility_ratio",
            "realized_vol",
            "mean_reversion_score",
            "return_dispersion",
            "directional_strength",
            "price_distance_from_mean",
        ]:
            assert a[name] == pytest.approx(b[name], abs=1e-9), (
                f"{name} should be scale-invariant: {a[name]} vs {b[name]}"
            )


class TestBarsInterface:
    def test_compute_from_bars_matches_compute_from_closes(self) -> None:
        rng = np.random.default_rng(5)
        log_returns = rng.normal(loc=0.0, scale=0.002, size=MIN_BARS_FOR_REGIME - 1)
        closes = 100.0 * np.exp(np.concatenate([[0.0], np.cumsum(log_returns)]))
        bars = _make_bars(closes.tolist())
        a = compute_regime_features(bars)
        b = compute_regime_features_from_closes(closes)
        assert a is not None and b is not None
        for name in REGIME_FEATURE_NAMES:
            assert a[name] == pytest.approx(b[name])
