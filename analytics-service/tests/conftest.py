"""Pytest path setup so ``src/analytics`` is importable as a top-level package."""

from __future__ import annotations

import sys
from pathlib import Path

_src = Path(__file__).resolve().parent.parent / "src"
if str(_src) not in sys.path:
    sys.path.insert(0, str(_src))

import numpy as np
import pytest

from analytics.portfolio.covariance import EstimatorSettings
from analytics.portfolio.reference import (
    CorrelationPrior,
    CostConfig,
    PortfolioReference,
    SymbolRef,
)

# Shared fixtures for the roadmap-4.6.1 portfolio/risk tests.

REPO_CONFIG = Path(__file__).resolve().parent.parent.parent / "config"

UNIVERSE = ("AAPL", "MSFT", "GOOGL", "AMZN", "TSLA", "NVDA")
ANNUAL_VOLS = np.array([0.28, 0.24, 0.27, 0.32, 0.55, 0.48])
BETAS = np.array([1.20, 0.95, 1.05, 1.20, 1.80, 1.65])
SECONDS_PER_YEAR = 5_896_800.0
BAR_SECONDS = 60


@pytest.fixture
def reference() -> PortfolioReference:
    """Six-symbol reference book mirroring ``config/portfolio.yml``, built in code.

    Constructed rather than loaded so the maths tests do not silently change meaning when
    somebody retunes the shipped YAML. ``test_reference.py`` covers the real file separately.
    """
    sectors = {
        "AAPL": "TECH",
        "MSFT": "TECH",
        "GOOGL": "TECH",
        "AMZN": "CONSUMER_DISCRETIONARY",
        "TSLA": "AUTOMOTIVE",
        "NVDA": "TECH",
    }
    caps = {
        "AAPL": 3.4e12,
        "MSFT": 3.1e12,
        "GOOGL": 2.1e12,
        "AMZN": 2.0e12,
        "TSLA": 8.0e11,
        "NVDA": 3.0e12,
    }
    advs = {
        "AAPL": 60e6,
        "MSFT": 25e6,
        "GOOGL": 25e6,
        "AMZN": 45e6,
        "TSLA": 90e6,
        "NVDA": 250e6,
    }
    return PortfolioReference(
        universe=UNIVERSE,
        symbols={
            symbol: SymbolRef(
                symbol=symbol,
                sector=sectors[symbol],
                beta=float(BETAS[i]),
                adv=advs[symbol],
                market_cap=caps[symbol],
                annualized_volatility=float(ANNUAL_VOLS[i]),
                half_spread_bps=1.0,
            )
            for i, symbol in enumerate(UNIVERSE)
        },
        risk_aversion=3.0,
        market_risk_aversion=2.5,
        tau=0.05,
        correlation_prior=CorrelationPrior(),
        cost=CostConfig(),
        source_path="fixture",
    )


@pytest.fixture
def estimator_settings() -> EstimatorSettings:
    return EstimatorSettings(
        estimator="ewma",
        ewma_lambda=0.97,
        shrinkage_floor=0.20,
        bar_seconds=BAR_SECONDS,
        seconds_per_year=SECONDS_PER_YEAR,
        trading_days_per_year=252.0,
        min_observations=30,
    )


@pytest.fixture
def factor_returns() -> np.ndarray:
    """400 bars of returns with a planted one-factor structure and known marginal volatilities.

    A single market factor scaled by each name's beta, plus idiosyncratic noise sized so the
    total per-bar variance matches ``ANNUAL_VOLS`` after annualisation. Deterministic seed.
    """
    rng = np.random.default_rng(20260729)
    periods = 400
    scale = np.sqrt(SECONDS_PER_YEAR / BAR_SECONDS)
    per_bar = ANNUAL_VOLS / scale
    market_vol = 0.18 / scale

    market = rng.normal(0.0, market_vol, size=(periods, 1))
    systematic_var = (BETAS * market_vol) ** 2
    idio_vol = np.sqrt(np.maximum(per_bar**2 - systematic_var, 1e-14))
    idio = rng.normal(0.0, 1.0, size=(periods, len(UNIVERSE))) * idio_vol
    return market @ BETAS.reshape(1, -1) + idio


@pytest.fixture
def daily_covariance() -> np.ndarray:
    """A simple two-asset daily covariance: 2% daily vol each, rho = 0.95."""
    sd = 0.02
    rho = 0.95
    return np.array([[sd * sd, rho * sd * sd], [rho * sd * sd, sd * sd]])
