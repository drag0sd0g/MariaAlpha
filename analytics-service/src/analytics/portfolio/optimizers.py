"""Portfolio optimisers — mean-variance, minimum variance, max Sharpe, risk parity.

**Mean-variance** (Markowitz 1952) maximises quadratic utility::

    max  mu'w - (lambda/2) w' Sigma w    s.t.  1'w = 1,  l <= w <= u

Unconstrained closed forms, used as warm starts and as the thing the tests check against:

- global minimum variance  ``w = Sigma^-1 1 / (1' Sigma^-1 1)``
- tangency / max Sharpe    ``w = Sigma^-1 mu / (1' Sigma^-1 mu)``

Every solve hands SLSQP an **analytic gradient** (``grad = mu - lambda Sigma w``). Finite-
difference gradients on a 50-dimensional problem are both slow and flaky near the bounds, and
the resulting "converged" answers are silently wrong.

**Max Sharpe under constraints** is a ratio, which SLSQP handles badly. Use the standard
Cornuejols-Tutuncu reformulation: minimise ``y' Sigma y`` subject to ``mu'y = 1``, ``y >= 0``,
then normalise ``w = y / 1'y``. Convex and reliable. It needs some feasible portfolio with
positive expected return; when none exists the optimiser says so instead of returning nonsense.

**Risk parity.** Portfolio volatility is homogeneous of degree one, so Euler gives::

    RC_i = w_i (Sigma w)_i / sigma_p,    sum_i RC_i = sigma_p

Equal risk contribution asks for ``RC_i = sigma_p / N``. The obvious objective
``min sum_ij (RC_i - RC_j)^2`` is non-convex with bad local minima. Spinu's (2013) log-barrier
form is strictly convex on the positive orthant::

    min  0.5 y' Sigma y - sum_i b_i ln(y_i),    then  w = y / 1'y

whose stationarity condition ``y_i (Sigma y)_i = b_i`` *is* proportional risk contribution with
budget ``b``. Solved over ``ln y`` so positivity is structural rather than a constraint, warm
started from inverse-volatility weights (the exact answer when correlations are equal).

**Baselines.** Equal weight and inverse volatility are shipped as first-class objectives, not as
afterthoughts: they are the benchmarks optimisers routinely fail to beat out of sample, and they
are closed-form, so there is always a working answer when a solver misbehaves.
"""

from __future__ import annotations

import math
import time
from dataclasses import dataclass, field
from enum import StrEnum
from typing import TYPE_CHECKING, Any

import numpy as np
from scipy import linalg, optimize

from analytics.metrics import OPTIMIZER_LATENCY, OPTIMIZER_RUNS
from analytics.portfolio.covariance import clean_matmul

if TYPE_CHECKING:
    from collections.abc import Sequence

    from analytics.numeric import FloatArray


class Objective(StrEnum):
    """Supported optimisation objectives."""

    MEAN_VARIANCE = "MEAN_VARIANCE"
    MIN_VARIANCE = "MIN_VARIANCE"
    MAX_SHARPE = "MAX_SHARPE"
    RISK_PARITY = "RISK_PARITY"
    EQUAL_WEIGHT = "EQUAL_WEIGHT"
    INVERSE_VOL = "INVERSE_VOL"


@dataclass(slots=True, frozen=True)
class Constraints:
    """Box, budget and sector constraints."""

    min_weight: float = 0.0
    max_weight: float = 1.0
    allow_shorts: bool = False
    max_sector_weight: dict[str, float] = field(default_factory=dict)
    risk_budget: dict[str, float] | None = None

    def bounds(self, n: int) -> list[tuple[float, float]]:
        low = self.min_weight if not self.allow_shorts else min(self.min_weight, -self.max_weight)
        high = self.max_weight
        if low > high:
            raise ValueError("min_weight exceeds max_weight")
        if n * high < 1.0 - 1e-12:
            raise ValueError(
                f"max_weight={high:g} across {n} assets cannot reach a fully invested "
                f"portfolio; raise max_weight to at least {1.0 / n:.4f}"
            )
        return [(low, high)] * n


@dataclass(slots=True, frozen=True)
class OptimizationResult:
    """The optimiser's answer plus enough diagnostics to distrust it."""

    objective: Objective
    symbols: tuple[str, ...]
    weights: FloatArray
    expected_return: float
    volatility: float
    sharpe: float
    risk_contributions: FloatArray
    diversification_ratio: float
    effective_n: float
    converged: bool
    iterations: int
    message: str

    def to_payload(self) -> dict[str, Any]:
        return {
            "objective": str(self.objective),
            "symbols": list(self.symbols),
            "weights": {
                s: round(float(w), 8) for s, w in zip(self.symbols, self.weights, strict=True)
            },
            "expectedReturn": round(self.expected_return, 8),
            "volatility": round(self.volatility, 8),
            "sharpe": round(self.sharpe, 6),
            "riskContributions": {
                s: round(float(r), 8)
                for s, r in zip(self.symbols, self.risk_contributions, strict=True)
            },
            "diversificationRatio": round(self.diversification_ratio, 6),
            "effectiveN": round(self.effective_n, 4),
            "converged": self.converged,
            "iterations": self.iterations,
            "message": self.message,
        }


@dataclass(slots=True, frozen=True)
class FrontierPoint:
    """One sample of the efficient frontier."""

    expected_return: float
    volatility: float
    sharpe: float
    weights: FloatArray

    def to_payload(self, symbols: Sequence[str]) -> dict[str, Any]:
        return {
            "expectedReturn": round(self.expected_return, 8),
            "volatility": round(self.volatility, 8),
            "sharpe": round(self.sharpe, 6),
            "weights": {s: round(float(w), 8) for s, w in zip(symbols, self.weights, strict=True)},
        }


# ------------------------------------------------------------------- primitives


def solve_spd(matrix: FloatArray, rhs: FloatArray) -> FloatArray:
    """Solve ``matrix x = rhs`` via Cholesky, jittering the diagonal if it is near-singular.

    ``numpy.linalg.inv`` is deliberately not used anywhere in this package: forming an explicit
    inverse and multiplying is both slower and numerically worse than a factor-and-solve, and on
    an ill-conditioned covariance it is the difference between a usable answer and noise.
    """
    n = matrix.shape[0]
    try:
        factor = linalg.cho_factor(matrix, lower=True)
    except linalg.LinAlgError:
        jitter = 1e-10 * max(float(np.trace(matrix)) / max(n, 1), 1e-12)
        factor = linalg.cho_factor(matrix + jitter * np.eye(n), lower=True)
    return np.asarray(linalg.cho_solve(factor, rhs), dtype=np.float64)


def portfolio_volatility(weights: FloatArray, cov: FloatArray) -> float:
    with clean_matmul():
        variance = float(weights @ cov @ weights)
    return math.sqrt(max(variance, 0.0))


def risk_contributions(weights: FloatArray, cov: FloatArray) -> FloatArray:
    """``RC_i = w_i (Sigma w)_i / sigma_p``. Sums to ``sigma_p`` exactly."""
    sigma = portfolio_volatility(weights, cov)
    if sigma <= 0:
        return np.zeros_like(weights)
    with clean_matmul():
        marginal = cov @ weights
    return weights * marginal / sigma


def diversification_ratio(weights: FloatArray, cov: FloatArray) -> float:
    """Weighted average volatility divided by portfolio volatility."""
    sigma = portfolio_volatility(weights, cov)
    if sigma <= 0:
        return float("inf")
    vols = np.sqrt(np.maximum(np.diag(cov), 0.0))
    return float(np.abs(weights) @ vols / sigma)


def effective_n(weights: FloatArray) -> float:
    """Inverse Herfindahl. ``N`` for equal weights, ``1`` for a single-name book."""
    denominator = float(np.sum(weights**2))
    return 1.0 / denominator if denominator > 0 else 0.0


def min_variance_closed_form(cov: FloatArray) -> FloatArray:
    ones = np.ones(cov.shape[0], dtype=np.float64)
    z = solve_spd(cov, ones)
    return z / float(ones @ z)


def tangency_closed_form(mu: FloatArray, cov: FloatArray) -> FloatArray:
    z = solve_spd(cov, mu)
    denominator = float(np.ones(cov.shape[0]) @ z)
    if abs(denominator) < 1e-15:
        raise ValueError("tangency portfolio is undefined (1' Sigma^-1 mu is zero)")
    return z / denominator


def inverse_vol_weights(cov: FloatArray) -> FloatArray:
    vols = np.sqrt(np.maximum(np.diag(cov), 1e-300))
    raw = 1.0 / vols
    return np.asarray(raw / raw.sum(), dtype=np.float64)


# ------------------------------------------------------------------- optimisers


def _budget_constraint() -> dict[str, Any]:
    return {
        "type": "eq",
        "fun": lambda w: float(np.sum(w) - 1.0),
        "jac": lambda w: np.ones_like(w),
    }


def _sector_constraints(
    symbols: Sequence[str], sectors: Sequence[str], caps: dict[str, float]
) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for sector, cap in caps.items():
        mask = np.array([s == sector for s in sectors], dtype=np.float64)
        if mask.sum() == 0:
            continue
        out.append(
            {
                "type": "ineq",  # cap - mask'w >= 0
                "fun": (lambda w, m=mask, c=cap: float(c - m @ w)),
                "jac": (lambda w, m=mask: -m),
            }
        )
    del symbols
    return out


def _mean_variance(
    mu: FloatArray,
    cov: FloatArray,
    constraints: Constraints,
    risk_aversion: float,
    sectors: Sequence[str],
    symbols: Sequence[str],
    x0: FloatArray,
) -> optimize.OptimizeResult:
    def objective(w: FloatArray) -> float:
        with clean_matmul():
            return float(-(mu @ w) + 0.5 * risk_aversion * (w @ cov @ w))

    def gradient(w: FloatArray) -> FloatArray:
        with clean_matmul():
            return -mu + risk_aversion * (cov @ w)

    cons: list[dict[str, Any]] = [_budget_constraint()]
    cons.extend(_sector_constraints(symbols, sectors, constraints.max_sector_weight))
    return optimize.minimize(
        objective,
        x0,
        jac=gradient,
        method="SLSQP",
        bounds=constraints.bounds(len(mu)),
        constraints=cons,
        options={"maxiter": 500, "ftol": 1e-12},
    )


def _max_sharpe(
    mu: FloatArray,
    cov: FloatArray,
    constraints: Constraints,
    sectors: Sequence[str],
    symbols: Sequence[str],
) -> tuple[FloatArray, bool, int, str]:
    """Cornuejols-Tutuncu reformulation, with a risk-aversion sweep as the fallback."""
    n = len(mu)
    if float(np.max(mu)) <= 0:
        # No long-only portfolio has positive expected return; the ratio has no maximum worth
        # reporting. Fall back to minimum variance and say so rather than return a sign-flipped
        # answer that looks like a recommendation.
        weights, ok, iters, msg = _run_mean_variance_family(
            np.zeros(n), cov, constraints, 1.0, sectors, symbols
        )
        return weights, ok, iters, "all expected returns are non-positive; returned min-variance"

    scale = constraints.max_weight

    def objective(y: FloatArray) -> float:
        with clean_matmul():
            return float(y @ cov @ y)

    def gradient(y: FloatArray) -> FloatArray:
        with clean_matmul():
            return 2.0 * (cov @ y)

    cons: list[dict[str, Any]] = [
        {
            "type": "eq",
            "fun": lambda y: float(mu @ y - 1.0),
            "jac": lambda y: mu,
        }
    ]
    # Box constraints transfer as  y_i <= max_weight * 1'y  and  y_i >= min_weight * 1'y.
    for i in range(n):
        cons.append(
            {
                "type": "ineq",
                "fun": (lambda y, k=i, c=scale: float(c * np.sum(y) - y[k])),
                "jac": (lambda y, k=i, c=scale: c * np.ones_like(y) - np.eye(len(y))[k]),
            }
        )
    for sector, cap in constraints.max_sector_weight.items():
        mask = np.array([s == sector for s in sectors], dtype=np.float64)
        if mask.sum() == 0:
            continue
        cons.append(
            {
                "type": "ineq",
                "fun": (lambda y, m=mask, c=cap: float(c * np.sum(y) - m @ y)),
                "jac": (lambda y, m=mask, c=cap: c * np.ones_like(y) - m),
            }
        )

    lower = 0.0 if not constraints.allow_shorts else -np.inf
    positive = mu > 0
    y0 = np.where(positive, 1.0, 0.0)
    y0 = y0 / max(float(mu @ y0), 1e-12)
    result = optimize.minimize(
        objective,
        y0,
        jac=gradient,
        method="SLSQP",
        bounds=[(lower, np.inf)] * n,
        constraints=cons,
        options={"maxiter": 500, "ftol": 1e-14},
    )
    total = float(np.sum(result.x))
    if not np.isfinite(total) or abs(total) < 1e-12:
        return (
            np.full(n, 1.0 / n),
            False,
            int(result.nit),
            "max-Sharpe reformulation degenerated; returned equal weight",
        )
    return (
        np.asarray(result.x / total, dtype=np.float64),
        bool(result.success),
        int(result.nit),
        str(result.message),
    )


def project_to_box_simplex(
    weights: FloatArray,
    low: float,
    high: float,
    max_iterations: int = 200,
    tolerance: float = 1e-12,
) -> FloatArray:
    """Nearest fully-invested point inside ``[low, high]``, by clip-and-redistribute.

    A single clip-then-renormalise does **not** work: renormalising scales the clipped entries
    back above the cap. This repeatedly clips, then pushes the remaining surplus or deficit onto
    the entries that still have room, which converges to a feasible point in a handful of passes.
    Redistribution is proportional rather than Euclidean so the shape of the input (the risk-parity
    tilt) survives as far as the box allows.
    """
    if low > high:
        raise ValueError("min_weight exceeds max_weight")
    n = weights.size
    if n * high < 1.0 - tolerance or n * low > 1.0 + tolerance:
        raise ValueError("box constraints cannot contain a fully invested portfolio")

    w = np.clip(weights.astype(np.float64, copy=True), low, high)
    for _ in range(max_iterations):
        total = float(w.sum())
        gap = 1.0 - total
        if abs(gap) <= tolerance:
            break
        room = (high - w) if gap > 0 else (w - low)
        capacity = float(room.sum())
        if capacity <= tolerance:
            break
        w = w + np.sign(gap) * room * (min(abs(gap), capacity) / capacity)
        w = np.clip(w, low, high)
    return w


def _risk_parity(
    cov: FloatArray,
    budget: FloatArray,
    constraints: Constraints,
) -> tuple[FloatArray, bool, int, str]:
    """Spinu log-barrier form, optimised over ``ln y`` so ``y > 0`` is structural.

    The barrier form has no notion of an upper bound, so any box is applied afterwards. When the
    box actually binds, the equal-risk-contribution property is genuinely lost — the ERC portfolio
    simply is not in the feasible set — and the result says so rather than reporting a
    "risk parity" answer whose contributions are not equal.
    """
    n = cov.shape[0]
    y0 = inverse_vol_weights(cov)

    def objective(log_y: FloatArray) -> float:
        y = np.exp(log_y)
        with clean_matmul():
            quadratic = float(y @ cov @ y)
        return 0.5 * quadratic - float(budget @ log_y)

    def gradient(log_y: FloatArray) -> FloatArray:
        y = np.exp(log_y)
        with clean_matmul():
            marginal = cov @ y
        # d/d(log y_i) = y_i * d/dy_i
        return np.asarray(y * marginal - budget, dtype=np.float64)

    result = optimize.minimize(
        objective,
        np.log(y0),
        jac=gradient,
        method="SLSQP",
        options={"maxiter": 1000, "ftol": 1e-16},
    )
    y = np.exp(result.x)
    unconstrained = y / float(np.sum(y))

    low, high = constraints.bounds(n)[0]
    weights = project_to_box_simplex(unconstrained, low, high)
    message = str(result.message)
    if not np.allclose(weights, unconstrained, atol=1e-9):
        message = (
            f"{message}; box constraints bind, so risk contributions are no longer exactly equal"
        )
    return weights, bool(result.success), int(result.nit), message


def _run_mean_variance_family(
    mu: FloatArray,
    cov: FloatArray,
    constraints: Constraints,
    risk_aversion: float,
    sectors: Sequence[str],
    symbols: Sequence[str],
) -> tuple[FloatArray, bool, int, str]:
    n = len(mu)
    x0 = np.clip(min_variance_closed_form(cov), *constraints.bounds(n)[0])
    total = float(np.sum(x0))
    x0 = x0 / total if abs(total) > 1e-12 else np.full(n, 1.0 / n)
    result = _mean_variance(mu, cov, constraints, risk_aversion, sectors, symbols, x0)
    weights: FloatArray = np.asarray(result.x, dtype=np.float64)
    total = float(np.sum(weights))
    if abs(total - 1.0) > 1e-6 and abs(total) > 1e-12:
        weights = weights / total
    return weights, bool(result.success), int(result.nit), str(result.message)


def optimize_portfolio(
    objective: Objective,
    symbols: Sequence[str],
    cov: FloatArray,
    mu: FloatArray | None = None,
    constraints: Constraints | None = None,
    risk_aversion: float = 3.0,
    sectors: Sequence[str] | None = None,
) -> OptimizationResult:
    """Run one optimisation and package the answer with its diagnostics."""
    symbols = tuple(symbols)
    n = len(symbols)
    if n == 0:
        raise ValueError("at least one symbol is required")
    if cov.shape != (n, n):
        raise ValueError(f"covariance must be {n}x{n}")
    cons = constraints if constraints is not None else Constraints()
    expected = np.zeros(n, dtype=np.float64) if mu is None else np.asarray(mu, dtype=np.float64)
    if expected.shape != (n,):
        raise ValueError(f"expected returns must have length {n}")
    sector_list = list(sectors) if sectors is not None else ["" for _ in symbols]

    started = time.perf_counter()
    converged, iterations, message = True, 0, "closed form"

    weights: FloatArray
    if objective is Objective.EQUAL_WEIGHT:
        weights = np.full(n, 1.0 / n, dtype=np.float64)
    elif objective is Objective.INVERSE_VOL:
        weights = inverse_vol_weights(cov)
    elif objective is Objective.MIN_VARIANCE:
        weights, converged, iterations, message = _run_mean_variance_family(
            np.zeros(n), cov, cons, 1.0, sector_list, symbols
        )
    elif objective is Objective.MEAN_VARIANCE:
        weights, converged, iterations, message = _run_mean_variance_family(
            expected, cov, cons, risk_aversion, sector_list, symbols
        )
    elif objective is Objective.MAX_SHARPE:
        weights, converged, iterations, message = _max_sharpe(
            expected, cov, cons, sector_list, symbols
        )
    elif objective is Objective.RISK_PARITY:
        budget = _risk_budget_vector(symbols, cons)
        weights, converged, iterations, message = _risk_parity(cov, budget, cons)
    else:  # pragma: no cover - StrEnum is exhaustive
        raise ValueError(f"unsupported objective: {objective}")

    OPTIMIZER_LATENCY.labels(objective=str(objective)).observe(time.perf_counter() - started)
    OPTIMIZER_RUNS.labels(objective=str(objective), converged=str(converged).lower()).inc()

    vol = portfolio_volatility(weights, cov)
    ret = float(expected @ weights)
    return OptimizationResult(
        objective=objective,
        symbols=symbols,
        weights=weights,
        expected_return=ret,
        volatility=vol,
        sharpe=(ret / vol) if vol > 0 else 0.0,
        risk_contributions=risk_contributions(weights, cov),
        diversification_ratio=diversification_ratio(weights, cov),
        effective_n=effective_n(weights),
        converged=converged,
        iterations=iterations,
        message=message,
    )


def _risk_budget_vector(symbols: Sequence[str], constraints: Constraints) -> FloatArray:
    n = len(symbols)
    if not constraints.risk_budget:
        return np.full(n, 1.0 / n, dtype=np.float64)
    raw = np.array(
        [max(float(constraints.risk_budget.get(s, 0.0)), 0.0) for s in symbols], dtype=np.float64
    )
    total = float(raw.sum())
    if total <= 0:
        return np.full(n, 1.0 / n, dtype=np.float64)
    return raw / total


def efficient_frontier(
    symbols: Sequence[str],
    mu: FloatArray,
    cov: FloatArray,
    constraints: Constraints | None = None,
    points: int = 25,
    sectors: Sequence[str] | None = None,
) -> list[FrontierPoint]:
    """Sample the frontier by sweeping risk aversion from very risk-tolerant to very averse.

    Sweeping ``lambda`` rather than solving ``min w'Sigma w  s.t.  mu'w = m`` avoids the
    infeasible-target problem: with box constraints, many nominal target returns simply cannot
    be reached, and the resulting failures make the frontier look ragged.
    """
    if points < 2:
        raise ValueError("points must be at least 2")
    cons = constraints if constraints is not None else Constraints()
    lambdas = np.logspace(-1.0, 2.5, points)
    out: list[FrontierPoint] = []
    for lam in lambdas:
        result = optimize_portfolio(
            Objective.MEAN_VARIANCE,
            symbols,
            cov,
            mu=mu,
            constraints=cons,
            risk_aversion=float(lam),
            sectors=sectors,
        )
        out.append(
            FrontierPoint(
                expected_return=result.expected_return,
                volatility=result.volatility,
                sharpe=result.sharpe,
                weights=result.weights,
            )
        )
    out.sort(key=lambda p: p.volatility)
    return out
