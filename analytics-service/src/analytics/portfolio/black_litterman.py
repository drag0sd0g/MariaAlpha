"""Black-Litterman — blend market-implied equilibrium returns with explicit views.

The problem BL solves is that sample means are hopeless as an input to ``Sigma^-1 mu``. The
standard error of a mean return estimated from ``T`` years of data is ``sigma / sqrt(T)``; at
25%/yr volatility and five years of history that is 11% — the same order of magnitude as the
quantity being estimated. Feed that into a mean-variance optimiser and you get the notorious
corner solutions: 90% in whichever asset happened to have the luckiest sample.

BL's answer: don't estimate ``mu`` at all. Start from the returns that the market's own
positioning already implies, and move away from them only as far as your views justify.

**Step 1 — reverse optimisation.** If the market-cap portfolio ``w_mkt`` is what a representative
investor with risk aversion ``lambda_mkt`` would hold, then the first-order condition of the
mean-variance problem run backwards gives the equilibrium excess returns::

    Pi = lambda_mkt Sigma w_mkt

**Step 2 — views.** ``K`` views as a pick matrix ``P (K x N)`` and a target vector ``Q (K)``.
A row picking one asset is an *absolute* view ("NVDA returns 12%"); a row summing to zero is a
*relative* view ("AAPL beats MSFT by 3%").

**Step 3 — view uncertainty.** He-Litterman's proportional convention::

    Omega = diag(tau P Sigma P') / confidence

so a view on a volatile combination is automatically held less tightly, and an explicit
per-view ``confidence`` scales it further.

**Step 4 — posterior** (Theil mixed estimation)::

    M      = [ (tau Sigma)^-1 + P' Omega^-1 P ]^-1
    E[R]   = M [ (tau Sigma)^-1 Pi + P' Omega^-1 Q ]
    Sigma_post = Sigma + M

Two equivalent implementations are provided. The default uses the Sherman-Morrison-Woodbury
identity, which only ever factors the ``K x K`` matrix ``Omega + tau P Sigma P'`` — with ``K``
typically 1-3 and ``N`` up to a few hundred that is both faster and better conditioned. The
naive form is kept because it is the literal transcription of the formulas above, and the tests
assert the two agree to 1e-10; an optimisation you cannot check against the definition is a
liability.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

import numpy as np

from analytics.portfolio.covariance import clean_matmul
from analytics.portfolio.optimizers import solve_spd

if TYPE_CHECKING:
    from collections.abc import Sequence

    from analytics.numeric import FloatArray


@dataclass(slots=True, frozen=True)
class View:
    """One investor view."""

    name: str
    pick: dict[str, float]
    expected_return: float
    confidence: float = 1.0

    def is_relative(self, tolerance: float = 1e-9) -> bool:
        return abs(sum(self.pick.values())) < tolerance


@dataclass(slots=True, frozen=True)
class BlackLittermanResult:
    """Equilibrium prior, posterior, and what the views actually did."""

    symbols: tuple[str, ...]
    equilibrium_returns: FloatArray
    posterior_returns: FloatArray
    posterior_covariance: FloatArray
    view_impact: FloatArray
    market_weights: FloatArray
    tau: float
    risk_aversion: float
    omega_diagonal: FloatArray
    view_names: tuple[str, ...] = ()
    warnings: tuple[str, ...] = field(default_factory=tuple)

    def to_payload(self) -> dict[str, Any]:
        return {
            "symbols": list(self.symbols),
            "equilibriumReturns": {
                s: round(float(v), 8)
                for s, v in zip(self.symbols, self.equilibrium_returns, strict=True)
            },
            "posteriorReturns": {
                s: round(float(v), 8)
                for s, v in zip(self.symbols, self.posterior_returns, strict=True)
            },
            "viewImpact": {
                s: round(float(v), 8) for s, v in zip(self.symbols, self.view_impact, strict=True)
            },
            "marketWeights": {
                s: round(float(v), 8)
                for s, v in zip(self.symbols, self.market_weights, strict=True)
            },
            "omegaDiagonal": {
                n: round(float(v), 10)
                for n, v in zip(self.view_names, self.omega_diagonal, strict=True)
            },
            "tau": self.tau,
            "riskAversion": self.risk_aversion,
            "warnings": list(self.warnings),
        }


def equilibrium_returns(
    cov: FloatArray, market_weights: FloatArray, risk_aversion: float
) -> FloatArray:
    """``Pi = lambda_mkt Sigma w_mkt`` — reverse optimisation."""
    with clean_matmul():
        return np.asarray(risk_aversion * (cov @ market_weights), dtype=np.float64)


def build_pick_matrix(
    views: Sequence[View], symbols: Sequence[str]
) -> tuple[FloatArray, FloatArray, list[str]]:
    """Assemble ``P`` and ``Q``. Raises on a view naming an unknown symbol."""
    index = {s: i for i, s in enumerate(symbols)}
    n, k = len(symbols), len(views)
    pick = np.zeros((k, n), dtype=np.float64)
    q = np.zeros(k, dtype=np.float64)
    warnings: list[str] = []
    for row, view in enumerate(views):
        if not view.pick:
            raise ValueError(f"view '{view.name}' has an empty pick")
        for symbol, coefficient in view.pick.items():
            if symbol not in index:
                raise ValueError(
                    f"view '{view.name}' references unknown symbol '{symbol}'; "
                    f"known symbols are {', '.join(symbols)}"
                )
            pick[row, index[symbol]] = float(coefficient)
        q[row] = float(view.expected_return)
        total = float(pick[row].sum())
        if abs(total) > 1e-9 and abs(total - 1.0) > 1e-9:
            warnings.append(
                f"view '{view.name}' coefficients sum to {total:.4f}; absolute views normally "
                "sum to 1 and relative views to 0"
            )
        if view.confidence <= 0:
            raise ValueError(f"view '{view.name}' needs a strictly positive confidence")
    return pick, q, warnings


def posterior(
    symbols: Sequence[str],
    cov: FloatArray,
    pi: FloatArray,
    views: Sequence[View],
    tau: float = 0.05,
    use_woodbury: bool = True,
) -> tuple[FloatArray, FloatArray, FloatArray, list[str]]:
    """Posterior mean, posterior covariance, ``diag(Omega)`` and any warnings."""
    n = len(symbols)
    if not views:
        # No views: the posterior is the prior, exactly. Worth short-circuiting rather than
        # pushing a K=0 matrix through the algebra and accumulating rounding.
        return pi.copy(), cov + tau * cov, np.zeros(0, dtype=np.float64), []

    pick, q, warnings = build_pick_matrix(views, symbols)
    tau_sigma = tau * cov

    with clean_matmul():
        pick_sigma_pick = pick @ tau_sigma @ pick.T
    omega_diag = np.maximum(np.diag(pick_sigma_pick).copy(), 1e-16)
    confidences = np.array([max(v.confidence, 1e-9) for v in views], dtype=np.float64)
    omega_diag = omega_diag / confidences
    omega = np.diag(omega_diag)

    if use_woodbury:
        # E[R] = Pi + tau Sigma P' (P tau Sigma P' + Omega)^-1 (Q - P Pi)
        with clean_matmul():
            middle = pick_sigma_pick + omega
            innovation = q - pick @ pi
        adjustment = solve_spd(middle, innovation)
        with clean_matmul():
            mean = pi + tau_sigma @ pick.T @ adjustment
            # M = tau Sigma - tau Sigma P' (P tau Sigma P' + Omega)^-1 P tau Sigma
            right = solve_spd(middle, pick @ tau_sigma)
            m_matrix = tau_sigma - tau_sigma @ pick.T @ right
    else:
        omega_inv = np.diag(1.0 / omega_diag)
        with clean_matmul():
            precision = _inverse_spd(tau_sigma, n) + pick.T @ omega_inv @ pick
            rhs = _inverse_spd(tau_sigma, n) @ pi + pick.T @ omega_inv @ q
        m_matrix = _inverse_spd(precision, n)
        with clean_matmul():
            mean = m_matrix @ rhs

    posterior_cov = cov + m_matrix
    return (
        np.asarray(mean, dtype=np.float64),
        np.asarray(posterior_cov, dtype=np.float64),
        omega_diag,
        warnings,
    )


def _inverse_spd(matrix: FloatArray, n: int) -> FloatArray:
    """Explicit inverse via repeated Cholesky solves — only used by the reference path."""
    return solve_spd(matrix, np.eye(n))


def run(
    symbols: Sequence[str],
    cov: FloatArray,
    market_weights: FloatArray,
    views: Sequence[View] = (),
    tau: float = 0.05,
    risk_aversion: float = 2.5,
    use_woodbury: bool = True,
) -> BlackLittermanResult:
    """Full Black-Litterman pass: equilibrium, views, posterior."""
    symbols = tuple(symbols)
    n = len(symbols)
    if cov.shape != (n, n):
        raise ValueError(f"covariance must be {n}x{n}")
    if market_weights.shape != (n,):
        raise ValueError(f"market weights must have length {n}")
    if tau <= 0:
        raise ValueError("tau must be positive")

    pi = equilibrium_returns(cov, market_weights, risk_aversion)
    mean, posterior_cov, omega_diag, warnings = posterior(
        symbols, cov, pi, views, tau=tau, use_woodbury=use_woodbury
    )
    return BlackLittermanResult(
        symbols=symbols,
        equilibrium_returns=pi,
        posterior_returns=mean,
        posterior_covariance=posterior_cov,
        view_impact=mean - pi,
        market_weights=market_weights,
        tau=tau,
        risk_aversion=risk_aversion,
        omega_diagonal=omega_diag,
        view_names=tuple(v.name for v in views),
        warnings=tuple(warnings),
    )
