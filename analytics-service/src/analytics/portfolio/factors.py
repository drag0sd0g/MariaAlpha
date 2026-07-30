"""Factor exposure decomposition — fundamental (BARRA-lite) and statistical (PCA).

Two decompositions, shipped together because each one checks the other.

**Fundamental / cross-sectional.** Build an exposure matrix ``B (N x K)`` from reference data —
market beta, size, volatility, momentum, and one dummy per sector — and read the portfolio's
exposure straight off as ``x = B'w``. The risk decomposition follows a factor model::

    r = B f + eps,     Sigma = B Sigma_f B' + Delta,     Delta = diag(specific variances)
    sigma_p^2 = w' B Sigma_f B' w  +  w' Delta w
                (systematic)          (idiosyncratic)

``Sigma_f`` is estimated by cross-sectional weighted least squares: at each time ``t``, regress
the cross-section of returns on ``B`` to recover factor returns ``f_t``, then take their
covariance. Weights are ``1/sigma_i``, the standard BARRA choice — an equal-weighted cross-
sectional regression lets the noisiest names dominate the factor estimate.

Per-factor variance contributions use Euler again, so they sum to the systematic share rather
than to something close to it.

**Statistical / PCA.** Eigen-decompose the *correlation* matrix and report variance explained per
component plus the portfolio's loading on each. This needs no reference data, always works, and
is the honest check on whether the fundamental factors actually span the risk: if PC1 explains
70% and your named factors only account for 30% of variance, your factor model is decorative.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

import numpy as np

from analytics.portfolio.covariance import clean_matmul

if TYPE_CHECKING:
    from collections.abc import Sequence

    from analytics.numeric import FloatArray
    from analytics.portfolio.reference import PortfolioReference

MARKET = "MARKET"
SIZE = "SIZE"
VOLATILITY = "VOLATILITY"
MOMENTUM = "MOMENTUM"
SECTOR_PREFIX = "SECTOR_"


@dataclass(slots=True, frozen=True)
class FactorModel:
    """Exposure matrix and the factor/specific covariance split."""

    symbols: tuple[str, ...]
    factors: tuple[str, ...]
    exposures: FloatArray
    factor_covariance: FloatArray
    specific_variance: FloatArray


@dataclass(slots=True, frozen=True)
class PcaResult:
    """Principal components of the correlation matrix."""

    variance_explained: FloatArray
    cumulative_variance_explained: FloatArray
    portfolio_loadings: FloatArray
    top_component_symbols: tuple[tuple[str, float], ...]

    def to_payload(self) -> dict[str, Any]:
        return {
            "varianceExplained": [round(float(v), 8) for v in self.variance_explained],
            "cumulativeVarianceExplained": [
                round(float(v), 8) for v in self.cumulative_variance_explained
            ],
            "portfolioLoadings": [round(float(v), 8) for v in self.portfolio_loadings],
            "topComponentSymbols": [
                {"symbol": s, "loading": round(float(v), 8)} for s, v in self.top_component_symbols
            ],
        }


@dataclass(slots=True, frozen=True)
class FactorDecomposition:
    """Everything the Factors tab needs."""

    symbols: tuple[str, ...]
    factors: tuple[str, ...]
    exposures: dict[str, float]
    variance_contributions: dict[str, float]
    systematic_variance: float
    idiosyncratic_variance: float
    covariance_variance: float
    pca: PcaResult
    notes: tuple[str, ...] = ()

    @property
    def model_variance(self) -> float:
        """The factor model's own variance. Sums by construction — the pie chart adds to 100%."""
        return self.systematic_variance + self.idiosyncratic_variance

    @property
    def systematic_pct(self) -> float:
        total = self.model_variance
        return self.systematic_variance / total if total > 0 else 0.0

    @property
    def model_fit(self) -> float:
        """Model variance over the covariance model's variance.

        A factor model is an *approximation* to Sigma: ``B Sigma_f B' + Delta`` matches Sigma's
        diagonal but not its off-diagonal, so the two variances differ. Reporting the ratio makes
        the approximation visible rather than passing the model's number off as the real one. A
        value far from 1.0 means the named factors do not span the portfolio's actual risk.
        """
        if self.covariance_variance <= 0:
            return 0.0
        return self.model_variance / self.covariance_variance

    def to_payload(self) -> dict[str, Any]:
        return {
            "symbols": list(self.symbols),
            "factors": list(self.factors),
            "exposures": {k: round(v, 8) for k, v in self.exposures.items()},
            "varianceContributions": {
                k: round(v, 10) for k, v in self.variance_contributions.items()
            },
            "systematicVariance": round(self.systematic_variance, 10),
            "idiosyncraticVariance": round(self.idiosyncratic_variance, 10),
            "modelVariance": round(self.model_variance, 10),
            "covarianceVariance": round(self.covariance_variance, 10),
            "modelFit": round(self.model_fit, 6),
            "systematicVariancePct": round(self.systematic_pct, 6),
            "idiosyncraticVariancePct": round(1.0 - self.systematic_pct, 6),
            "modelVolatility": round(float(np.sqrt(max(self.model_variance, 0.0))), 8),
            "covarianceVolatility": round(float(np.sqrt(max(self.covariance_variance, 0.0))), 8),
            "pca": self.pca.to_payload(),
            "notes": list(self.notes),
        }


def _zscore(values: FloatArray) -> FloatArray:
    """Cross-sectional z-score. A degenerate cross-section scores flat rather than exploding."""
    std = float(np.std(values))
    if std < 1e-12:
        return np.zeros_like(values)
    return (values - float(np.mean(values))) / std


def build_exposures(
    symbols: Sequence[str],
    reference: PortfolioReference,
    volatilities: FloatArray,
    momentum: FloatArray | None = None,
) -> tuple[FloatArray, tuple[str, ...]]:
    """Assemble ``B`` and the factor names."""
    n = len(symbols)
    betas = reference.betas(symbols)
    caps = np.array([max(reference.ref(s).market_cap, 1.0) for s in symbols], dtype=np.float64)
    sectors = reference.sectors(symbols)
    unique_sectors = sorted(set(sectors))

    columns: list[FloatArray] = [betas, _zscore(np.log(caps)), _zscore(volatilities)]
    names: list[str] = [MARKET, SIZE, VOLATILITY]

    if momentum is not None and momentum.size == n:
        columns.append(_zscore(momentum))
        names.append(MOMENTUM)

    for sector in unique_sectors:
        columns.append(np.array([1.0 if s == sector else 0.0 for s in sectors], dtype=np.float64))
        names.append(f"{SECTOR_PREFIX}{sector}")

    return np.column_stack(columns), tuple(names)


def estimate_factor_model(
    symbols: Sequence[str],
    reference: PortfolioReference,
    covariance: FloatArray,
    returns: FloatArray | None = None,
) -> FactorModel:
    """Cross-sectional WLS factor returns, or a covariance-implied fallback.

    With live returns, ``Sigma_f`` comes from regressing each period's cross-section on ``B``.
    Without them (the cold-start case), ``Sigma_f`` is derived by projecting the *prior*
    covariance onto the factor space via the pseudo-inverse — a weaker statement, but one that
    keeps the systematic/idiosyncratic split defined instead of returning zeros.
    """
    symbols = tuple(symbols)
    volatilities = np.sqrt(np.maximum(np.diag(covariance), 0.0))
    momentum = None
    if returns is not None and returns.shape[0] >= 2 and returns.shape[1] == len(symbols):
        momentum = returns.sum(axis=0)

    exposures, names = build_exposures(symbols, reference, volatilities, momentum)
    k = exposures.shape[1]

    weights = 1.0 / np.maximum(volatilities, 1e-8)
    weighted = exposures * weights[:, None]

    if (
        returns is not None
        and returns.shape[0] >= max(k + 1, 3)
        and returns.shape[1] == len(symbols)
    ):
        with clean_matmul():
            gram = weighted.T @ weighted
            rhs = weighted.T @ (returns * weights[None, :]).T
        factor_returns = np.linalg.lstsq(gram, rhs, rcond=None)[0].T
        factor_cov = np.cov(factor_returns, rowvar=False, ddof=1)
        factor_cov = np.atleast_2d(factor_cov)
        with clean_matmul():
            residuals = returns - factor_returns @ exposures.T
        specific = np.var(residuals, axis=0, ddof=1)
        # Rescale to the annualised covariance the rest of the system uses.
        per_bar_var = np.maximum(np.var(returns, axis=0, ddof=1), 1e-30)
        scale = np.maximum(np.diag(covariance), 0.0) / per_bar_var
        mean_scale = float(np.mean(scale)) if np.isfinite(scale).all() else 1.0
        factor_cov = factor_cov * mean_scale
        specific = specific * scale
    else:
        # No return history: project the covariance onto the factor space. Use the *unweighted*
        # pseudo-inverse so that ``B Sigma_f B' = P Sigma P'`` with ``P = B pinv(B)`` a genuine
        # orthogonal projector onto col(B) — mixing the WLS-weighted pseudo-inverse with an
        # unweighted reconstruction gives a "systematic" number that corresponds to no projection
        # at all, and the split silently fails to reconcile.
        pseudo = np.linalg.pinv(exposures)
        with clean_matmul():
            factor_cov = pseudo @ covariance @ pseudo.T
            fitted = exposures @ factor_cov @ exposures.T
        specific = np.maximum(np.diag(covariance) - np.diag(fitted), 0.0)

    factor_cov = 0.5 * (factor_cov + factor_cov.T)
    return FactorModel(
        symbols=symbols,
        factors=names,
        exposures=exposures,
        factor_covariance=factor_cov,
        specific_variance=np.maximum(specific, 0.0),
    )


def pca(
    weights: FloatArray,
    correlation: FloatArray,
    volatilities: FloatArray,
    symbols: Sequence[str],
    top: int = 5,
) -> PcaResult:
    """Principal components of the correlation matrix, largest eigenvalue first."""
    n = correlation.shape[0]
    eigenvalues, eigenvectors = np.linalg.eigh(correlation)
    order = np.argsort(eigenvalues)[::-1]
    eigenvalues = np.maximum(eigenvalues[order], 0.0)
    eigenvectors = eigenvectors[:, order]

    total = float(eigenvalues.sum())
    explained = eigenvalues / total if total > 0 else np.zeros(n)

    scaled = weights * volatilities
    with clean_matmul():
        loadings = eigenvectors.T @ scaled

    first = eigenvectors[:, 0]
    ranked = sorted(zip(symbols, first, strict=True), key=lambda pair: -abs(float(pair[1])))[:top]

    return PcaResult(
        variance_explained=explained,
        cumulative_variance_explained=np.cumsum(explained),
        portfolio_loadings=loadings,
        top_component_symbols=tuple((s, float(v)) for s, v in ranked),
    )


def decompose(
    symbols: Sequence[str],
    weights: FloatArray,
    covariance: FloatArray,
    correlation: FloatArray,
    reference: PortfolioReference,
    returns: FloatArray | None = None,
) -> FactorDecomposition:
    """Full decomposition: exposures, per-factor variance contributions, PCA."""
    symbols = tuple(symbols)
    model = estimate_factor_model(symbols, reference, covariance, returns)

    with clean_matmul():
        exposure_vector = model.exposures.T @ weights
        factor_marginal = model.factor_covariance @ exposure_vector
        systematic = float(exposure_vector @ factor_marginal)
        idiosyncratic = float(weights @ (model.specific_variance * weights))
        total = float(weights @ covariance @ weights)

    contributions = {
        name: float(exposure_vector[i] * factor_marginal[i]) for i, name in enumerate(model.factors)
    }

    notes: list[str] = []
    if len(model.factors) >= len(symbols):
        notes.append(
            f"the model is saturated: {len(model.factors)} factors span {len(symbols)} assets, so "
            "the factor space is the whole return space and every unit of risk is classified as "
            "systematic by construction. Interpret the exposures, not the "
            "systematic/idiosyncratic split, until the universe is wider than the factor set."
        )
    if returns is None:
        notes.append(
            "no live return history: the factor covariance is a projection of the prior "
            "covariance onto the factor space rather than a cross-sectional regression"
        )

    volatilities = np.sqrt(np.maximum(np.diag(covariance), 0.0))
    return FactorDecomposition(
        symbols=symbols,
        factors=model.factors,
        exposures={name: float(exposure_vector[i]) for i, name in enumerate(model.factors)},
        variance_contributions=contributions,
        systematic_variance=max(systematic, 0.0),
        idiosyncratic_variance=max(idiosyncratic, 0.0),
        covariance_variance=max(total, 0.0),
        pca=pca(weights, correlation, volatilities, symbols),
        notes=tuple(notes),
    )
