"""Unit tests for technical indicator functions."""

import numpy as np
import pytest

from ml_signal.features.indicators import atr, ema, macd, rsi


class TestEMA:
    def test_single_element(self) -> None:
        result = ema(np.array([10.0]), period=3)
        assert result[0] == pytest.approx(10.0)

    def test_known_values(self) -> None:
        """EMA(3) with alpha = 0.5 and known hand-computed values."""
        values = np.array([10.0, 11.0, 12.0, 13.0, 14.0])
        result = ema(values, period=3)  # alpha = 2/(3+1) = 0.5
        assert result[0] == pytest.approx(10.0)
        assert result[1] == pytest.approx(10.5)
        assert result[2] == pytest.approx(11.25)
        assert result[3] == pytest.approx(12.125)
        assert result[4] == pytest.approx(13.0625)

    def test_constant_series(self) -> None:
        """EMA of a constant series equals the constant."""
        values = np.full(50, 42.0)
        result = ema(values, period=20)
        assert result[-1] == pytest.approx(42.0)

    def test_output_length_matches_input(self) -> None:
        values = np.arange(100.0)
        result = ema(values, period=20)
        assert len(result) == len(values)


class TestRSI:
    def test_monotonic_up_high_rsi(self) -> None:
        """A steady uptrend should produce RSI near 100."""
        closes = np.arange(1.0, 30.0)
        result = rsi(closes, period=14)
        assert result[-1] > 90

    def test_monotonic_down_low_rsi(self) -> None:
        """A steady downtrend should produce RSI near 0."""
        closes = np.arange(30.0, 1.0, -1.0)
        result = rsi(closes, period=14)
        assert result[-1] < 10

    def test_alternating_near_50(self) -> None:
        """Alternating up/down should produce RSI near 50."""
        closes = np.array([100.0 + (i % 2) for i in range(50)], dtype=np.float64)
        result = rsi(closes, period=14)
        assert 40 < result[-1] < 60

    def test_insufficient_data_returns_50(self) -> None:
        """With fewer bars than period, RSI defaults to 50."""
        closes = np.array([10.0, 11.0, 12.0])
        result = rsi(closes, period=14)
        np.testing.assert_array_equal(result, 50.0)

    def test_output_length_matches_input(self) -> None:
        closes = np.arange(1.0, 50.0)
        result = rsi(closes, period=14)
        assert len(result) == len(closes)


class TestMACD:
    def test_flat_series_near_zero(self) -> None:
        """Flat prices should produce MACD values near zero."""
        closes = np.full(50, 100.0)
        macd_line, signal_line, histogram = macd(closes)
        assert abs(macd_line[-1]) < 0.01
        assert abs(signal_line[-1]) < 0.01
        assert abs(histogram[-1]) < 0.01

    def test_uptrend_positive_macd(self) -> None:
        """In an uptrend, MACD line should be positive (fast EMA > slow EMA)."""
        closes = np.linspace(100, 200, 60)
        macd_line, _, _ = macd(closes)
        assert macd_line[-1] > 0

    def test_three_outputs(self) -> None:
        closes = np.arange(1.0, 50.0)
        result = macd(closes)
        assert len(result) == 3
        assert all(len(r) == len(closes) for r in result)


class TestATR:
    def test_constant_range(self) -> None:
        """Bars with constant H-L range and no gaps should converge to that range."""
        n = 30
        highs = np.full(n, 110.0)
        lows = np.full(n, 100.0)
        closes = np.full(n, 105.0)
        result = atr(highs, lows, closes, period=14)
        assert result[-1] == pytest.approx(10.0, abs=0.5)

    def test_with_gap(self) -> None:
        """A gap (prev close outside current H-L) should increase true range."""
        highs = np.array([110.0, 110.0, 110.0, 110.0, 115.0] * 4, dtype=np.float64)
        lows = np.array([100.0, 100.0, 100.0, 100.0, 105.0] * 4, dtype=np.float64)
        closes = np.array([105.0, 105.0, 105.0, 105.0, 112.0] * 4, dtype=np.float64)
        result = atr(highs, lows, closes, period=14)
        assert result[-1] > 0

    def test_output_length_matches_input(self) -> None:
        n = 30
        result = atr(np.ones(n) * 110, np.ones(n) * 100, np.ones(n) * 105, period=14)
        assert len(result) == n
