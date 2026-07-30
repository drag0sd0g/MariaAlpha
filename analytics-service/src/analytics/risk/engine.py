"""Firm-wide risk engine — VaR, expected shortfall, component VaR, diversification credit.

Three VaR methods, deliberately reported side by side rather than picking one, because their
disagreement is the information:

**Parametric (variance-covariance).** ``sigma_p = sqrt(v' Sigma_d v)`` with ``v`` the *signed*
notional vector, then ``VaR = z_alpha sigma_p sqrt(h)`` and, for a Gaussian,
``ES = phi(z_alpha)/(1-alpha) sigma_p sqrt(h)`` (so ES/VaR is a fixed 1.2465 at 95%). Fast and
differentiable; wrong in the tail, because equity returns are leptokurtic.

**Historical simulation.** Revalue today's book under every historical return vector:
``PnL_t = v' r_t``, then read the empirical quantile. No distributional assumption, so it keeps
whatever fat tails and correlation breakdown are in the sample — but it cannot produce a loss
larger than the worst thing in the window, and the window here is short. The engine reports
``observations`` and flags ``sufficient = False`` below ``2/(1-alpha)`` samples rather than
returning a confident number built on nine data points.

**Monte Carlo.** Cholesky-factor the daily covariance, draw ``n`` standard normals (or Student-t
scaled to unit variance for fat tails), form ``r = L z sqrt(h)`` and read the quantile of
``v' r``. Seeded, so CI is deterministic. This is also the only one of the three that extends to
non-linear instruments, which is why the plumbing is worth having before options become book
positions.

**Component VaR.** ``sigma_p`` is homogeneous of degree one in ``v``, so Euler's theorem gives an
*exact* additive decomposition::

    MVaR_i = z_alpha (Sigma_d v)_i / sigma_p sqrt(h)      marginal
    CVaR_i = v_i MVaR_i                                    component
    sum_i CVaR_i = VaR                                     exactly

Component VaR goes **negative** for a genuine hedge. That is precisely the information a
sum-of-absolutes aggregation destroys, and it is why this table is the headline of the risk page.

**Diversification ratio.** ``DR = (sum_i z sigma_i |v_i|) / VaR_p`` — the old model's number
divided by the new one. Every response carries both, so the value of the upgrade stays auditable
instead of being a claim in a pull-request description.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Any, Literal

import numpy as np
import structlog
from scipy import linalg, stats

from analytics.metrics import (
    DIVERSIFICATION_RATIO,
    PORTFOLIO_ES_USD,
    PORTFOLIO_VAR_USD,
)
from analytics.portfolio.covariance import clean_matmul

if TYPE_CHECKING:
    from collections.abc import Callable, Sequence

    from analytics.config import Settings
    from analytics.numeric import FloatArray
    from analytics.portfolio.reference import PortfolioReference
    from analytics.portfolio.service import CovarianceService
    from analytics.portfolio.state import PortfolioSnapshot, PortfolioState
    from analytics.risk.stress import Scenario, ScenarioResult

logger = structlog.get_logger()

VarMethod = Literal["PARAMETRIC", "HISTORICAL", "MONTE_CARLO"]


@dataclass(slots=True, frozen=True)
class VarResult:
    """One VaR/ES estimate plus the caveats that make it interpretable."""

    method: VarMethod
    confidence: float
    horizon_days: float
    var_usd: float
    expected_shortfall_usd: float
    portfolio_volatility_usd: float
    observations: int | None = None
    simulations: int | None = None
    sufficient: bool = True
    notes: tuple[str, ...] = ()

    def to_payload(self) -> dict[str, Any]:
        return {
            "method": self.method,
            "confidence": self.confidence,
            "horizonDays": self.horizon_days,
            "varUsd": round(self.var_usd, 2),
            "expectedShortfallUsd": round(self.expected_shortfall_usd, 2),
            "portfolioVolatilityUsd": round(self.portfolio_volatility_usd, 2),
            "observations": self.observations,
            "simulations": self.simulations,
            "sufficient": self.sufficient,
            "notes": list(self.notes),
        }


@dataclass(slots=True, frozen=True)
class ComponentVarRow:
    """Euler decomposition of portfolio VaR onto one position."""

    symbol: str
    notional_usd: float
    standalone_var_usd: float
    marginal_var_usd: float
    component_var_usd: float
    pct_of_total: float

    def to_payload(self) -> dict[str, Any]:
        return {
            "symbol": self.symbol,
            "notionalUsd": round(self.notional_usd, 2),
            "standaloneVarUsd": round(self.standalone_var_usd, 2),
            "marginalVarUsd": round(self.marginal_var_usd, 6),
            "componentVarUsd": round(self.component_var_usd, 2),
            "pctOfTotal": round(self.pct_of_total, 6),
        }


@dataclass(slots=True, frozen=True)
class RiskReport:
    """Everything the risk page and the HTML report need, in one immutable object."""

    as_of: datetime
    symbols: tuple[str, ...]
    nav_usd: float
    gross_exposure_usd: float
    net_exposure_usd: float
    confidence: float
    horizon_days: float
    parametric: VarResult
    historical: VarResult | None
    monte_carlo: VarResult | None
    components: tuple[ComponentVarRow, ...]
    diversification_ratio: float
    sum_of_absolutes_var_usd: float
    var_limit_usd: float
    breaches_var_limit: bool
    scenarios: tuple[ScenarioResult, ...] = ()
    diagnostics: dict[str, Any] = field(default_factory=dict)

    def to_payload(self) -> dict[str, Any]:
        return {
            "asOf": self.as_of.isoformat(),
            "symbols": list(self.symbols),
            "navUsd": round(self.nav_usd, 2),
            "grossExposureUsd": round(self.gross_exposure_usd, 2),
            "netExposureUsd": round(self.net_exposure_usd, 2),
            "confidence": self.confidence,
            "horizonDays": self.horizon_days,
            "parametric": self.parametric.to_payload(),
            "historical": self.historical.to_payload() if self.historical else None,
            "monteCarlo": self.monte_carlo.to_payload() if self.monte_carlo else None,
            "components": [c.to_payload() for c in self.components],
            "diversificationRatio": round(self.diversification_ratio, 4),
            "sumOfAbsolutesVarUsd": round(self.sum_of_absolutes_var_usd, 2),
            "varLimitUsd": self.var_limit_usd,
            "breachesVarLimit": self.breaches_var_limit,
            "scenarios": [s.to_payload() for s in self.scenarios],
            "diagnostics": self.diagnostics,
        }


# ------------------------------------------------------------------ pure functions


def z_score(confidence: float) -> float:
    """One-tail normal quantile. ``z(0.95) = 1.6449``, ``z(0.99) = 2.3263``."""
    if not 0.0 < confidence < 1.0:
        raise ValueError("confidence must be strictly between 0 and 1")
    return float(stats.norm.ppf(confidence))


def gaussian_es_multiplier(confidence: float) -> float:
    """``phi(z_alpha) / (1 - alpha)`` — the Gaussian ES-to-sigma multiplier."""
    z = z_score(confidence)
    return float(stats.norm.pdf(z) / (1.0 - confidence))


def portfolio_sigma(notionals: FloatArray, daily_cov: FloatArray) -> float:
    """``sqrt(v' Sigma_d v)`` in USD. Signed notionals, so hedges net down."""
    if notionals.size == 0:
        return 0.0
    with clean_matmul():
        variance = float(notionals @ daily_cov @ notionals)
    if not math.isfinite(variance):
        raise ValueError("portfolio variance is not finite")
    return math.sqrt(max(variance, 0.0))


def parametric_var(
    notionals: FloatArray,
    daily_cov: FloatArray,
    confidence: float,
    horizon_days: float,
) -> VarResult:
    sigma = portfolio_sigma(notionals, daily_cov) * math.sqrt(horizon_days)
    z = z_score(confidence)
    return VarResult(
        method="PARAMETRIC",
        confidence=confidence,
        horizon_days=horizon_days,
        var_usd=z * sigma,
        expected_shortfall_usd=gaussian_es_multiplier(confidence) * sigma,
        portfolio_volatility_usd=sigma,
    )


def empirical_var_es(pnl: FloatArray, confidence: float) -> tuple[float, float]:
    """VaR and ES from a P&L sample. Both are reported as positive loss numbers."""
    if pnl.size == 0:
        return 0.0, 0.0
    var = -float(np.quantile(pnl, 1.0 - confidence, method="linear"))
    tail = pnl[pnl <= -var]
    es = -float(tail.mean()) if tail.size > 0 else var
    return var, max(es, var)


def historical_var(
    notionals: FloatArray,
    returns: FloatArray,
    confidence: float,
    horizon_days: float,
    bars_per_day: float,
) -> VarResult:
    """Full revaluation of the current book under each historical return vector.

    Bars are aggregated into non-overlapping horizon blocks when there are enough of them —
    that is the assumption-free version. When there are not (the usual case on an intraday
    window), the per-bar P&L distribution is scaled by sqrt-of-time and a note says so, because
    that step quietly reintroduces the i.i.d. assumption historical simulation exists to avoid.
    """
    notes: list[str] = []
    if returns.size == 0 or notionals.size == 0:
        return VarResult(
            method="HISTORICAL",
            confidence=confidence,
            horizon_days=horizon_days,
            var_usd=0.0,
            expected_shortfall_usd=0.0,
            portfolio_volatility_usd=0.0,
            observations=0,
            sufficient=False,
            notes=("no return history available",),
        )

    with clean_matmul():
        per_bar_pnl = returns @ notionals
    per_bar_pnl = per_bar_pnl[np.isfinite(per_bar_pnl)]

    bars_per_horizon = max(int(round(bars_per_day * horizon_days)), 1)
    blocks = per_bar_pnl.size // bars_per_horizon
    min_blocks_needed = int(math.ceil(2.0 / (1.0 - confidence)))

    if blocks >= min_blocks_needed:
        usable = per_bar_pnl[: blocks * bars_per_horizon].reshape(blocks, bars_per_horizon)
        pnl = usable.sum(axis=1)
        notes.append(f"non-overlapping {bars_per_horizon}-bar blocks")
        observations = int(blocks)
    else:
        pnl = per_bar_pnl * math.sqrt(bars_per_horizon)
        notes.append(
            f"per-bar P&L scaled by sqrt({bars_per_horizon}); "
            "too few bars for non-overlapping horizon blocks"
        )
        observations = int(per_bar_pnl.size)

    var, es = empirical_var_es(pnl, confidence)
    sufficient = observations >= min_blocks_needed
    if not sufficient:
        notes.append(
            f"only {observations} observations; {min_blocks_needed} needed at "
            f"{confidence:.0%} confidence"
        )
    return VarResult(
        method="HISTORICAL",
        confidence=confidence,
        horizon_days=horizon_days,
        var_usd=var,
        expected_shortfall_usd=es,
        portfolio_volatility_usd=float(np.std(pnl, ddof=1)) if pnl.size > 1 else 0.0,
        observations=observations,
        sufficient=sufficient,
        notes=tuple(notes),
    )


def monte_carlo_var(
    notionals: FloatArray,
    daily_cov: FloatArray,
    confidence: float,
    horizon_days: float,
    simulations: int = 10_000,
    distribution: Literal["normal", "t"] = "normal",
    df: float = 5.0,
    seed: int | None = 20260729,
) -> VarResult:
    """Simulate the horizon P&L distribution from the covariance model."""
    notes: list[str] = []
    if notionals.size == 0:
        return VarResult(
            method="MONTE_CARLO",
            confidence=confidence,
            horizon_days=horizon_days,
            var_usd=0.0,
            expected_shortfall_usd=0.0,
            portfolio_volatility_usd=0.0,
            simulations=0,
            notes=("empty portfolio",),
        )

    n = notionals.size
    # Jitter the diagonal rather than fail: a shrunk covariance is PSD but can still be
    # numerically singular when two symbols are near-collinear.
    try:
        chol = linalg.cholesky(daily_cov, lower=True)
    except linalg.LinAlgError:
        jitter = 1e-12 * max(float(np.trace(daily_cov)) / n, 1e-12)
        chol = linalg.cholesky(daily_cov + jitter * np.eye(n), lower=True)
        notes.append("covariance required a diagonal jitter before Cholesky")

    rng = np.random.default_rng(seed)
    if distribution == "t":
        if df <= 2.0:
            raise ValueError("Student-t degrees of freedom must exceed 2 for finite variance")
        # Rescale so the marginal variance still matches the covariance model; without this the
        # t draws would inflate volatility as well as the tail, which double-counts the effect.
        raw = rng.standard_t(df, size=(simulations, n))
        z = raw * math.sqrt((df - 2.0) / df)
        notes.append(f"Student-t innovations, df={df:g}, rescaled to unit variance")
    else:
        z = rng.standard_normal(size=(simulations, n))

    with clean_matmul():
        shocks = (z @ chol.T) * math.sqrt(horizon_days)
        pnl = shocks @ notionals

    var, es = empirical_var_es(pnl, confidence)
    return VarResult(
        method="MONTE_CARLO",
        confidence=confidence,
        horizon_days=horizon_days,
        var_usd=var,
        expected_shortfall_usd=es,
        portfolio_volatility_usd=float(np.std(pnl, ddof=1)),
        simulations=int(simulations),
        notes=tuple(notes),
    )


def component_var(
    symbols: Sequence[str],
    notionals: FloatArray,
    daily_cov: FloatArray,
    confidence: float,
    horizon_days: float,
) -> tuple[tuple[ComponentVarRow, ...], float, float]:
    """Euler component VaR plus the sum-of-absolutes number and the diversification ratio."""
    z = z_score(confidence)
    sqrt_h = math.sqrt(horizon_days)
    daily_vols = np.sqrt(np.maximum(np.diag(daily_cov), 0.0))
    standalone = z * np.abs(notionals) * daily_vols * sqrt_h
    sum_of_absolutes = float(standalone.sum())

    sigma = portfolio_sigma(notionals, daily_cov)
    total_var = z * sigma * sqrt_h

    if sigma <= 0 or total_var <= 0:
        rows = tuple(
            ComponentVarRow(
                symbol=s,
                notional_usd=float(notionals[i]),
                standalone_var_usd=float(standalone[i]),
                marginal_var_usd=0.0,
                component_var_usd=0.0,
                pct_of_total=0.0,
            )
            for i, s in enumerate(symbols)
        )
        # A perfectly hedged book has zero VaR; the ratio is unbounded, so report the
        # sum-of-absolutes number as the ratio's numerator and cap it rather than emit inf.
        ratio = float("inf") if sum_of_absolutes > 0 else 1.0
        return rows, sum_of_absolutes, ratio

    with clean_matmul():
        cov_v = daily_cov @ notionals
    marginal = z * cov_v / sigma * sqrt_h
    component = notionals * marginal

    rows = tuple(
        ComponentVarRow(
            symbol=s,
            notional_usd=float(notionals[i]),
            standalone_var_usd=float(standalone[i]),
            marginal_var_usd=float(marginal[i]),
            component_var_usd=float(component[i]),
            pct_of_total=float(component[i] / total_var),
        )
        for i, s in enumerate(symbols)
    )
    return rows, sum_of_absolutes, sum_of_absolutes / total_var


# ------------------------------------------------------------------ the service


class RiskEngine:
    """Assembles a full :class:`RiskReport` from the live (or a supplied) book."""

    def __init__(
        self,
        settings: Settings,
        covariance_service: CovarianceService,
        portfolio_state: PortfolioState,
        reference: PortfolioReference,
        scenarios: Sequence[Scenario] = (),
        market_cache: Any = None,
        alert_publisher: Callable[[dict[str, object]], None] | None = None,
    ) -> None:
        self._settings = settings
        self._covariance = covariance_service
        self._state = portfolio_state
        self._reference = reference
        self._scenarios = tuple(scenarios)
        self._market_cache = market_cache
        self._publish = alert_publisher
        self._last_alert_epoch = 0.0

    @property
    def scenarios(self) -> tuple[Scenario, ...]:
        return self._scenarios

    def evaluate(
        self,
        snapshot: PortfolioSnapshot | None = None,
        confidence: float | None = None,
        horizon_days: float | None = None,
        simulations: int | None = None,
        distribution: Literal["normal", "t"] | None = None,
        include_scenarios: bool = True,
    ) -> RiskReport:
        s = self._settings
        book = snapshot if snapshot is not None else self._state.snapshot()
        conf = confidence if confidence is not None else s.risk_confidence_level
        horizon = horizon_days if horizon_days is not None else s.risk_horizon_days

        symbols = book.symbols
        if not symbols:
            return self._empty_report(book, conf, horizon)

        estimate = self._covariance.current(symbols)
        daily_cov = estimate.daily()
        notionals = book.notionals()

        parametric = parametric_var(notionals, daily_cov, conf, horizon)
        components, sum_abs, ratio = component_var(symbols, notionals, daily_cov, conf, horizon)

        historical = self._historical(symbols, notionals, conf, horizon)
        monte_carlo = monte_carlo_var(
            notionals,
            daily_cov,
            conf,
            horizon,
            simulations=simulations if simulations is not None else s.risk_mc_simulations,
            distribution=distribution if distribution is not None else s.risk_mc_distribution,
            df=s.risk_mc_df,
            seed=s.risk_mc_seed,
        )

        scenarios: tuple[ScenarioResult, ...] = ()
        if include_scenarios and self._scenarios:
            from analytics.risk.stress import apply_scenarios

            scenarios = apply_scenarios(
                self._scenarios,
                book,
                self._reference,
                estimate,
                loss_limit_usd=s.stress_loss_limit_usd,
            )

        report = RiskReport(
            as_of=datetime.now(UTC),
            symbols=symbols,
            nav_usd=book.nav_usd,
            gross_exposure_usd=book.gross_exposure_usd,
            net_exposure_usd=book.net_exposure_usd,
            confidence=conf,
            horizon_days=horizon,
            parametric=parametric,
            historical=historical,
            monte_carlo=monte_carlo,
            components=components,
            diversification_ratio=ratio,
            sum_of_absolutes_var_usd=sum_abs,
            var_limit_usd=s.risk_var_limit_usd,
            breaches_var_limit=parametric.var_usd > s.risk_var_limit_usd > 0,
            scenarios=scenarios,
            diagnostics=estimate.diagnostics(),
        )
        self._record_metrics(report)
        return report

    def evaluate_limits(self) -> RiskReport | None:
        """Periodic check that publishes breach alerts. Called by the risk-model loop."""
        book = self._state.snapshot()
        if not book.symbols:
            return None
        report = self.evaluate(book)
        if self._publish is None:
            return report

        if report.breaches_var_limit:
            self._publish(
                {
                    "type": "PORTFOLIO_VAR_BREACH",
                    "severity": "HIGH",
                    "symbol": "PORTFOLIO",
                    "varUsd": round(report.parametric.var_usd, 2),
                    "limitUsd": report.var_limit_usd,
                    "confidence": report.confidence,
                    "diversificationRatio": round(report.diversification_ratio, 4),
                    "detectedAt": report.as_of.isoformat(),
                }
            )
        for scenario in report.scenarios:
            if scenario.breaches_limit:
                self._publish(
                    {
                        "type": "STRESS_LIMIT_BREACH",
                        "severity": "HIGH",
                        "symbol": scenario.worst_symbol or "PORTFOLIO",
                        "scenario": scenario.name,
                        "pnlUsd": round(scenario.pnl_usd, 2),
                        "limitUsd": self._settings.stress_loss_limit_usd,
                        "detectedAt": report.as_of.isoformat(),
                    }
                )
        return report

    def _historical(
        self,
        symbols: Sequence[str],
        notionals: FloatArray,
        confidence: float,
        horizon_days: float,
    ) -> VarResult | None:
        if self._market_cache is None:
            return None
        s = self._settings
        returns, live = self._market_cache.returns_matrix(
            symbols,
            bar_seconds=s.returns_bar_seconds,
            max_bars=s.returns_max_bars,
            clock=s.returns_clock,
        )
        if returns.size == 0 or not live:
            return historical_var(notionals, returns, confidence, horizon_days, 1.0)
        # Restrict the notional vector to the symbols that actually have history.
        index = {sym: i for i, sym in enumerate(symbols)}
        sub = np.array([notionals[index[sym]] for sym in live], dtype=np.float64)
        bars_per_day = s.trading_seconds_per_year / s.trading_days_per_year / s.returns_bar_seconds
        result = historical_var(sub, returns, confidence, horizon_days, bars_per_day)
        if len(live) < len(symbols):
            missing = [sym for sym in symbols if sym not in set(live)]
            result = VarResult(
                method=result.method,
                confidence=result.confidence,
                horizon_days=result.horizon_days,
                var_usd=result.var_usd,
                expected_shortfall_usd=result.expected_shortfall_usd,
                portfolio_volatility_usd=result.portfolio_volatility_usd,
                observations=result.observations,
                simulations=result.simulations,
                sufficient=False,
                notes=(*result.notes, f"excludes symbols without history: {', '.join(missing)}"),
            )
        return result

    def _empty_report(
        self, book: PortfolioSnapshot, confidence: float, horizon: float
    ) -> RiskReport:
        empty = VarResult(
            method="PARAMETRIC",
            confidence=confidence,
            horizon_days=horizon,
            var_usd=0.0,
            expected_shortfall_usd=0.0,
            portfolio_volatility_usd=0.0,
            notes=("portfolio is empty",),
        )
        return RiskReport(
            as_of=datetime.now(UTC),
            symbols=(),
            nav_usd=book.nav_usd,
            gross_exposure_usd=0.0,
            net_exposure_usd=0.0,
            confidence=confidence,
            horizon_days=horizon,
            parametric=empty,
            historical=None,
            monte_carlo=None,
            components=(),
            diversification_ratio=1.0,
            sum_of_absolutes_var_usd=0.0,
            var_limit_usd=self._settings.risk_var_limit_usd,
            breaches_var_limit=False,
            diagnostics={"source": "empty-portfolio"},
        )

    @staticmethod
    def _record_metrics(report: RiskReport) -> None:
        conf = f"{report.confidence:.2f}"
        PORTFOLIO_VAR_USD.labels(method="parametric", confidence=conf).set(
            report.parametric.var_usd
        )
        PORTFOLIO_ES_USD.labels(method="parametric", confidence=conf).set(
            report.parametric.expected_shortfall_usd
        )
        if report.historical is not None:
            PORTFOLIO_VAR_USD.labels(method="historical", confidence=conf).set(
                report.historical.var_usd
            )
            PORTFOLIO_ES_USD.labels(method="historical", confidence=conf).set(
                report.historical.expected_shortfall_usd
            )
        if report.monte_carlo is not None:
            PORTFOLIO_VAR_USD.labels(method="monte_carlo", confidence=conf).set(
                report.monte_carlo.var_usd
            )
            PORTFOLIO_ES_USD.labels(method="monte_carlo", confidence=conf).set(
                report.monte_carlo.expected_shortfall_usd
            )
        if math.isfinite(report.diversification_ratio):
            DIVERSIFICATION_RATIO.set(report.diversification_ratio)
