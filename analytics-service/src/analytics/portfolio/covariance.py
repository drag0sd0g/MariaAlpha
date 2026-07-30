"""Covariance estimation — sample, EWMA, Ledoit-Wolf shrinkage, PSD repair.

Everything downstream (optimisers, VaR, factor risk, the pre-trade gate in execution-engine)
reduces to one matrix, so this module is where the honesty has to live.

**Why not just the sample covariance.** ``S`` estimates ``N(N+1)/2`` parameters from ``T x N``
numbers. When ``T`` is comparable to ``N`` its extreme eigenvalues are badly biased — the largest
too large, the smallest too small (Marchenko-Pastur) — and inverting it, which every optimiser
does, amplifies exactly the directions that are least well estimated. With ``T < N`` it is
singular outright.

**EWMA.** ``Sigma_t = lam Sigma_{t-1} + (1 - lam) r_t r_t'``. Equivalent to a weighted sample
covariance with weights ``alpha lam^(T-1-t)``, ``alpha = (1 - lam) / (1 - lam^T)``. Captures
volatility clustering, which an equal-weighted window averages away.

**Ledoit-Wolf shrinkage** (*Honey, I Shrunk the Sample Covariance Matrix*, 2004). Blend the noisy
estimator toward a structured target::

    Sigma_hat = delta F + (1 - delta) S

with ``F`` the constant-correlation target (sample variances on the diagonal, the average sample
correlation everywhere off it). The optimal intensity minimises expected Frobenius loss::

    delta* = clip((pi_hat - rho_hat) / gamma_hat / T, 0, 1)

``pi_hat`` is the summed asymptotic variance of the sample covariances, ``rho_hat`` the summed
asymptotic covariance between target and sample, ``gamma_hat = ||F - S||_F^2`` the target's
misspecification. Implemented from the paper's Appendix B rather than borrowing
``sklearn.covariance.LedoitWolf``, which shrinks toward a *scaled identity* — a target that
throws away the correlation structure this whole feature exists to capture.

**PSD repair.** Eigenvalue clipping with a diagonal rescale so marginal volatilities survive the
repair. Reported through ``psd_repaired`` — silently repairing a matrix is how people end up
trusting a number that has been quietly rewritten.

**Annualisation.** ``Sigma_annual = Sigma_bar * (seconds_per_year / bar_seconds)`` and
``sigma_daily = sigma_annual / sqrt(252)``. Square-root-of-time is wrong (returns are
autocorrelated, volatility clusters) and is what every desk uses; the existing
``IntradayVarCheck`` already assumes it, so the convention is at least consistent.
"""

from __future__ import annotations

import contextlib
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Literal

import numpy as np

if TYPE_CHECKING:
    from collections.abc import Iterator, Sequence

    from analytics.numeric import FloatArray
    from analytics.portfolio.reference import PortfolioReference

# Below this many aligned bars the sample estimate is noise; fall back to the prior.
MIN_SAMPLE_OBSERVATIONS = 5


@contextlib.contextmanager
def clean_matmul() -> Iterator[None]:
    """Suppress spurious FP-status warnings raised by Apple Accelerate's BLAS.

    numpy built against Accelerate (the default on macOS/arm64) leaves the divide-by-zero,
    overflow and invalid flags set after a perfectly ordinary ``matmul``, so numpy reports three
    RuntimeWarnings on results that are entirely finite. Linux wheels build against OpenBLAS and
    never do this. Rather than silence the warnings globally, the callers wrap only the matmul
    and then assert finiteness themselves via :func:`_require_finite`.
    """
    with np.errstate(divide="ignore", over="ignore", invalid="ignore"):
        yield


def _require_finite(matrix: FloatArray, what: str) -> FloatArray:
    if not np.isfinite(matrix).all():
        raise ValueError(f"{what} produced non-finite entries; the return series is unusable")
    return matrix


@dataclass(slots=True, frozen=True)
class CovarianceEstimate:
    """An annualised covariance matrix plus everything needed to judge it."""

    symbols: tuple[str, ...]
    covariance: FloatArray
    correlation: FloatArray
    volatilities: FloatArray
    observations: int
    bar_seconds: int
    shrinkage_intensity: float
    source: str
    psd_repaired: bool
    condition_number: float
    symbols_from_prior: tuple[str, ...]
    trading_days_per_year: float = 252.0

    def daily(self) -> FloatArray:
        """Daily covariance — what VaR at a one-day horizon needs."""
        return np.asarray(self.covariance / self.trading_days_per_year, dtype=np.float64)

    def daily_volatilities(self) -> FloatArray:
        return np.asarray(self.volatilities / np.sqrt(self.trading_days_per_year), dtype=np.float64)

    def index_of(self, symbol: str) -> int | None:
        try:
            return self.symbols.index(symbol)
        except ValueError:
            return None

    def subset(self, symbols: Sequence[str]) -> CovarianceEstimate:
        """Restrict to ``symbols``, which must all be present."""
        idx = []
        for s in symbols:
            i = self.index_of(s)
            if i is None:
                raise ValueError(f"symbol {s} is not in the covariance estimate")
            idx.append(i)
        sel = np.array(idx, dtype=int)
        cov = self.covariance[np.ix_(sel, sel)]
        return CovarianceEstimate(
            symbols=tuple(symbols),
            covariance=cov,
            correlation=self.correlation[np.ix_(sel, sel)],
            volatilities=self.volatilities[sel],
            observations=self.observations,
            bar_seconds=self.bar_seconds,
            shrinkage_intensity=self.shrinkage_intensity,
            source=self.source,
            psd_repaired=self.psd_repaired,
            condition_number=float(np.linalg.cond(cov)) if cov.size else 1.0,
            symbols_from_prior=tuple(s for s in self.symbols_from_prior if s in symbols),
            trading_days_per_year=self.trading_days_per_year,
        )

    def diagnostics(self) -> dict[str, Any]:
        return {
            "source": self.source,
            "observations": self.observations,
            "barSeconds": self.bar_seconds,
            "shrinkageIntensity": round(self.shrinkage_intensity, 6),
            "psdRepaired": self.psd_repaired,
            "conditionNumber": round(self.condition_number, 4),
            "symbolsFromPrior": list(self.symbols_from_prior),
            "tradingDaysPerYear": self.trading_days_per_year,
        }

    def to_payload(self) -> dict[str, Any]:
        return {
            "symbols": list(self.symbols),
            "annualizedVolatility": [round(float(v), 8) for v in self.volatilities],
            "correlation": [[round(float(c), 8) for c in row] for row in self.correlation],
            "diagnostics": self.diagnostics(),
        }


# --------------------------------------------------------------------- estimators


def sample_covariance(returns: FloatArray, ddof: int = 1) -> FloatArray:
    """Equal-weighted sample covariance of a ``T x N`` return matrix."""
    if returns.ndim != 2 or returns.shape[0] <= ddof:
        raise ValueError("need a 2-D return matrix with more rows than ddof")
    centered = returns - returns.mean(axis=0, keepdims=True)
    with clean_matmul():
        cov = (centered.T @ centered) / (returns.shape[0] - ddof)
    return _require_finite(cov, "sample covariance")


def ewma_weights(n: int, lam: float) -> FloatArray:
    """Normalised exponential weights, oldest first. ``lam -> 1`` gives equal weights."""
    if not 0.0 < lam <= 1.0:
        raise ValueError("lambda must be in (0, 1]")
    if lam == 1.0:
        return np.full(n, 1.0 / n, dtype=np.float64)
    powers = np.arange(n - 1, -1, -1, dtype=np.float64)
    raw = (1.0 - lam) * lam**powers
    return np.asarray(raw / raw.sum(), dtype=np.float64)


def ewma_covariance(returns: FloatArray, lam: float = 0.97) -> FloatArray:
    """RiskMetrics-style exponentially weighted covariance."""
    if returns.ndim != 2 or returns.shape[0] < 2:
        raise ValueError("need a 2-D return matrix with at least two rows")
    w = ewma_weights(returns.shape[0], lam)
    mean = (w[:, None] * returns).sum(axis=0, keepdims=True)
    centered = returns - mean
    with clean_matmul():
        cov = (centered * w[:, None]).T @ centered
    return _require_finite(cov, "EWMA covariance")


def constant_correlation_target(sample: FloatArray) -> FloatArray:
    """Ledoit-Wolf's structured target: sample variances, one shared off-diagonal correlation."""
    variances = np.diag(sample).copy()
    std = np.sqrt(np.maximum(variances, 1e-300))
    corr = sample / np.outer(std, std)
    n = sample.shape[0]
    if n < 2:
        return sample.copy()
    off_diag = corr[~np.eye(n, dtype=bool)]
    mean_corr = float(np.mean(off_diag))
    target = mean_corr * np.outer(std, std)
    np.fill_diagonal(target, variances)
    return target


def ledoit_wolf_intensity(
    returns: FloatArray,
    sample: FloatArray,
    target: FloatArray,
) -> float:
    """Optimal shrinkage intensity toward the constant-correlation target (LW 2004, App. B)."""
    t, n = returns.shape
    if t < 2 or n < 2:
        return 1.0
    centered = returns - returns.mean(axis=0, keepdims=True)

    # pi: summed asymptotic variance of the entries of the sample covariance.
    squared = centered**2
    with clean_matmul():
        pi_matrix = (
            (squared.T @ squared) / t - 2.0 * (centered.T @ centered) / t * sample + sample**2
        )
    if not np.isfinite(pi_matrix).all():
        return 1.0
    pi_hat = float(pi_matrix.sum())

    variances = np.diag(sample)
    std = np.sqrt(np.maximum(variances, 1e-300))
    corr = sample / np.outer(std, std)
    off_diag = corr[~np.eye(n, dtype=bool)]
    mean_corr = float(np.mean(off_diag))

    # rho: diagonal terms contribute pi directly; off-diagonal terms pick up the
    # constant-correlation target's dependence on the sample variances.
    with clean_matmul():
        theta_ii = (centered**3).T @ centered / t - variances * sample
    if not np.isfinite(theta_ii).all():
        return 1.0
    rho_diag = float(np.sum(np.diag(pi_matrix)))
    ratio = np.outer(std, 1.0 / std)
    off_mask = ~np.eye(n, dtype=bool)
    rho_off = float(
        (mean_corr / 2.0) * np.sum((ratio * theta_ii.T + ratio.T * theta_ii) * off_mask)
    )
    rho_hat = rho_diag + rho_off

    gamma_hat = float(np.sum((target - sample) ** 2))
    if gamma_hat <= 0.0:
        return 1.0
    kappa = (pi_hat - rho_hat) / gamma_hat
    return float(np.clip(kappa / t, 0.0, 1.0))


def repair_psd(matrix: FloatArray, eps: float = 1e-8) -> tuple[FloatArray, bool]:
    """Clip negative eigenvalues, then rescale the diagonal to preserve marginal variances."""
    if matrix.size == 0:
        return matrix, False
    symmetric = 0.5 * (matrix + matrix.T)
    eigenvalues, eigenvectors = np.linalg.eigh(symmetric)
    floor = eps * max(float(eigenvalues.max()), 1e-300)
    if float(eigenvalues.min()) >= floor:
        return symmetric, False
    clipped = np.maximum(eigenvalues, floor)
    with clean_matmul():
        repaired = eigenvectors @ np.diag(clipped) @ eigenvectors.T
    original_diag = np.diag(symmetric).copy()
    repaired_diag = np.diag(repaired).copy()
    scale = np.sqrt(
        np.divide(
            np.maximum(original_diag, 0.0),
            np.maximum(repaired_diag, 1e-300),
            out=np.ones_like(original_diag),
            where=repaired_diag > 1e-300,
        )
    )
    repaired = repaired * np.outer(scale, scale)
    repaired = 0.5 * (repaired + repaired.T)
    return repaired, True


def annualize(cov_per_bar: FloatArray, bar_seconds: int, seconds_per_year: float) -> FloatArray:
    """Scale a per-bar covariance to annual under square-root-of-time."""
    if bar_seconds <= 0:
        raise ValueError("bar_seconds must be positive")
    return cov_per_bar * (seconds_per_year / float(bar_seconds))


def to_correlation(cov: FloatArray) -> tuple[FloatArray, FloatArray]:
    """Split a covariance into (volatilities, correlation)."""
    vols = np.sqrt(np.maximum(np.diag(cov), 0.0))
    safe = np.where(vols > 0, vols, 1.0)
    corr = cov / np.outer(safe, safe)
    corr = np.clip(0.5 * (corr + corr.T), -1.0, 1.0)
    np.fill_diagonal(corr, 1.0)
    return vols, corr


# ------------------------------------------------------------------ orchestration


@dataclass(slots=True, frozen=True)
class EstimatorSettings:
    """The knobs ``estimate`` needs, decoupled from the pydantic Settings object."""

    estimator: Literal["sample", "ewma"] = "ewma"
    ewma_lambda: float = 0.97
    shrinkage_floor: float = 0.20
    bar_seconds: int = 60
    seconds_per_year: float = 5_896_800.0
    trading_days_per_year: float = 252.0
    min_observations: int = 30


def estimate(
    symbols: Sequence[str],
    reference: PortfolioReference,
    settings: EstimatorSettings,
    returns: FloatArray | None = None,
    return_symbols: Sequence[str] | None = None,
) -> CovarianceEstimate:
    """Blend a live sample estimate with the configured prior.

    ``returns`` is a ``T x N_r`` matrix whose columns are ``return_symbols`` — a subset of
    ``symbols``, because a name can be in the universe without having ticked yet. Symbols with
    no live data keep their prior row and column, and are named in ``symbols_from_prior`` so a
    reader can see which numbers are estimated and which are asserted.
    """
    symbols = tuple(symbols)
    if not symbols:
        raise ValueError("at least one symbol is required")

    prior_cov = reference.prior_covariance(symbols)
    have_sample = (
        returns is not None
        and returns.size > 0
        and returns.shape[0] >= max(settings.min_observations, MIN_SAMPLE_OBSERVATIONS)
        and return_symbols is not None
        and len(return_symbols) > 0
    )

    if not have_sample:
        vols, corr = to_correlation(prior_cov)
        repaired_cov, repaired = repair_psd(prior_cov)
        vols, corr = to_correlation(repaired_cov)
        return CovarianceEstimate(
            symbols=symbols,
            covariance=repaired_cov,
            correlation=corr,
            volatilities=vols,
            observations=0 if returns is None else int(returns.shape[0]),
            bar_seconds=settings.bar_seconds,
            shrinkage_intensity=1.0,
            source="prior",
            psd_repaired=repaired,
            condition_number=_cond(repaired_cov),
            symbols_from_prior=symbols,
            trading_days_per_year=settings.trading_days_per_year,
        )

    assert returns is not None and return_symbols is not None  # noqa: S101 - narrowed above
    live = tuple(return_symbols)

    sample_bar = (
        ewma_covariance(returns, settings.ewma_lambda)
        if settings.estimator == "ewma"
        else sample_covariance(returns)
    )
    sample_annual = annualize(sample_bar, settings.bar_seconds, settings.seconds_per_year)

    prior_live = reference.prior_covariance(live)
    target = constant_correlation_target(sample_annual)
    # Blend the data-driven target with the configured prior so the shrinkage destination still
    # carries the sector structure rather than a single averaged correlation.
    target = 0.5 * (target + prior_live)

    intensity = ledoit_wolf_intensity(returns, sample_annual, target)
    intensity = float(np.clip(max(intensity, settings.shrinkage_floor), 0.0, 1.0))
    blended_live = intensity * target + (1.0 - intensity) * sample_annual

    # Splice the estimated block back into the prior for the full universe.
    full = prior_cov.copy()
    positions = {s: i for i, s in enumerate(symbols)}
    live_idx = np.array([positions[s] for s in live if s in positions], dtype=int)
    kept = [s for s in live if s in positions]
    if len(kept) > 0:
        sub = np.array([live.index(s) for s in kept], dtype=int)
        full[np.ix_(live_idx, live_idx)] = blended_live[np.ix_(sub, sub)]

    repaired_cov, repaired = repair_psd(full)
    vols, corr = to_correlation(repaired_cov)
    from_prior = tuple(s for s in symbols if s not in set(kept))

    return CovarianceEstimate(
        symbols=symbols,
        covariance=repaired_cov,
        correlation=corr,
        volatilities=vols,
        observations=int(returns.shape[0]),
        bar_seconds=settings.bar_seconds,
        shrinkage_intensity=intensity,
        source="sample+prior",
        psd_repaired=repaired,
        condition_number=_cond(repaired_cov),
        symbols_from_prior=from_prior,
        trading_days_per_year=settings.trading_days_per_year,
    )


def from_supplied(
    symbols: Sequence[str],
    covariance: FloatArray,
    trading_days_per_year: float = 252.0,
    bar_seconds: int = 60,
) -> CovarianceEstimate:
    """Wrap a caller-supplied annualised covariance — what-if analysis and deterministic tests."""
    symbols = tuple(symbols)
    cov = np.asarray(covariance, dtype=np.float64)
    if cov.shape != (len(symbols), len(symbols)):
        raise ValueError(f"covariance must be {len(symbols)}x{len(symbols)}")
    if not np.isfinite(cov).all():
        raise ValueError("covariance contains non-finite entries")
    repaired_cov, repaired = repair_psd(cov)
    vols, corr = to_correlation(repaired_cov)
    return CovarianceEstimate(
        symbols=symbols,
        covariance=repaired_cov,
        correlation=corr,
        volatilities=vols,
        observations=0,
        bar_seconds=bar_seconds,
        shrinkage_intensity=0.0,
        source="supplied",
        psd_repaired=repaired,
        condition_number=_cond(repaired_cov),
        symbols_from_prior=(),
        trading_days_per_year=trading_days_per_year,
    )


def _cond(matrix: FloatArray) -> float:
    if matrix.size == 0:
        return 1.0
    value = float(np.linalg.cond(matrix))
    return value if np.isfinite(value) else float("inf")
