"""``CovarianceService`` — glue between the tick cache, the prior and the estimator.

Holds a short-lived cache of the last estimate so a burst of API calls (the UI opens six tabs at
once) does not re-run the estimator six times over the same ticks.
"""

from __future__ import annotations

import threading
import time
from typing import TYPE_CHECKING

import structlog

from analytics.metrics import COVARIANCE_OBSERVATIONS, COVARIANCE_SHRINKAGE
from analytics.portfolio.covariance import CovarianceEstimate, EstimatorSettings, estimate

if TYPE_CHECKING:
    from collections.abc import Sequence

    from analytics.config import Settings
    from analytics.consumer.market_data import MarketDataCache
    from analytics.portfolio.reference import PortfolioReference

logger = structlog.get_logger()

CACHE_TTL_SECONDS = 15.0


class CovarianceService:
    """Estimates (and caches) the covariance model for the configured universe."""

    def __init__(
        self,
        settings: Settings,
        market_cache: MarketDataCache,
        reference: PortfolioReference,
    ) -> None:
        self._settings = settings
        self._cache = market_cache
        self._reference = reference
        self._lock = threading.RLock()
        self._cached: CovarianceEstimate | None = None
        self._cached_at = 0.0
        self._cached_key: tuple[str, ...] = ()

    def universe(self) -> tuple[str, ...]:
        """Configured universe, falling back to whatever has actually ticked."""
        if self._reference.universe:
            return tuple(self._reference.universe)
        return self._cache.tracked_symbols()

    def estimator_settings(self) -> EstimatorSettings:
        s = self._settings
        return EstimatorSettings(
            estimator=s.covariance_estimator,
            ewma_lambda=s.covariance_ewma_lambda,
            shrinkage_floor=s.covariance_shrinkage_floor,
            bar_seconds=s.returns_bar_seconds,
            seconds_per_year=s.trading_seconds_per_year,
            trading_days_per_year=s.trading_days_per_year,
            min_observations=s.returns_min_observations,
        )

    def current(self, symbols: Sequence[str] | None = None) -> CovarianceEstimate:
        """Estimate for ``symbols`` (default: the universe), served from cache when fresh."""
        target = tuple(symbols) if symbols else self.universe()
        if not target:
            raise ValueError("no symbols configured and no market data seen yet")

        with self._lock:
            fresh = (
                self._cached is not None
                and self._cached_key == target
                and (time.monotonic() - self._cached_at) < CACHE_TTL_SECONDS
            )
            if fresh and self._cached is not None:
                return self._cached

        result = self._estimate(target)

        with self._lock:
            self._cached = result
            self._cached_at = time.monotonic()
            self._cached_key = target
        COVARIANCE_OBSERVATIONS.set(result.observations)
        COVARIANCE_SHRINKAGE.set(result.shrinkage_intensity)
        return result

    def invalidate(self) -> None:
        with self._lock:
            self._cached = None

    def _estimate(self, symbols: tuple[str, ...]) -> CovarianceEstimate:
        s = self._settings
        returns, live_symbols = self._cache.returns_matrix(
            symbols,
            bar_seconds=s.returns_bar_seconds,
            max_bars=s.returns_max_bars,
            clock=s.returns_clock,
        )
        result = estimate(
            symbols=symbols,
            reference=self._reference,
            settings=self.estimator_settings(),
            returns=returns if returns.size else None,
            return_symbols=live_symbols if live_symbols else None,
        )
        logger.debug(
            "covariance_estimated",
            symbols=len(symbols),
            source=result.source,
            observations=result.observations,
            shrinkage=round(result.shrinkage_intensity, 4),
        )
        return result
