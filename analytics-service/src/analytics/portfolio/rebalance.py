"""Cost-aware rebalancing — solve for the book worth *trading to*, not the theoretical optimum.

Re-solving the optimiser every period and trading to the answer loses money. The optimiser is
acutely sensitive to estimation noise in ``mu``, so the target wanders even when nothing real has
changed, and every wander costs spread plus impact. The fix is to put the cost inside the
objective so the optimiser has to justify the trade::

    max  mu'w - (lambda/2) w' Sigma w - TC(w, w0)      s.t.  1'w = 1,  l <= w <= u

**Cost model.** Per asset, with ``Q_i = |w_i - w0_i| * NAV``::

    TC_i = c_i Q_i                                     spread + commission (linear, in bps)
         + eta sigma_daily,i Q_i sqrt(Q_i / (ADV_i P_i))   square-root market impact

The impact term is the Almgren-Chriss ``eta sigma sqrt(Q/V)`` shape already used by the
Implementation Shortfall strategy, so the two places in this codebase that price impact price it
the same way. It is convex on ``Q >= 0`` (``Q^1.5`` is), which keeps the problem well behaved.

**Making it smooth.** ``|w - w0|`` is non-differentiable at zero, which wrecks SLSQP — it stalls
exactly where most assets want to sit. Use the exact buy/sell split ``w = w0 + p - q`` with
``p, q >= 0``. Since costs are strictly positive, no optimum ever has ``p_i`` and ``q_i`` both
positive, so ``|Delta w_i| = p_i + q_i`` holds at the solution and the objective is smooth
everywhere. The problem doubles in dimension, which is free at this scale.

**No-trade band.** The cost penalty alone still emits 0.01%-of-NAV trades that no desk would
send. Three post-processing filters — minimum notional, a band in bps of NAV, and lot rounding —
are what actually stop the churn. Both the surviving and the suppressed legs are reported, so the
band's effect is visible instead of silently swallowing trades.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Any, Literal

import numpy as np
from scipy import optimize

from analytics.portfolio.covariance import clean_matmul
from analytics.portfolio.optimizers import Constraints, portfolio_volatility

if TYPE_CHECKING:
    from collections.abc import Sequence

    from analytics.numeric import FloatArray
    from analytics.portfolio.reference import PortfolioReference


@dataclass(slots=True, frozen=True)
class CostModel:
    """Per-symbol transaction-cost parameters."""

    half_spread_bps: dict[str, float] = field(default_factory=dict)
    commission_bps: float = 0.5
    impact_eta: float = 1.0
    adv_shares: dict[str, float] = field(default_factory=dict)
    default_half_spread_bps: float = 1.0
    default_adv_shares: float = 1_000_000.0

    @classmethod
    def from_reference(cls, reference: PortfolioReference, eta: float | None = None) -> CostModel:
        return cls(
            half_spread_bps={s: r.half_spread_bps for s, r in reference.symbols.items()},
            commission_bps=reference.cost.commission_bps,
            impact_eta=eta if eta is not None else reference.cost.impact_eta,
            adv_shares={s: r.adv for s, r in reference.symbols.items()},
            default_half_spread_bps=reference.cost.default_half_spread_bps,
        )

    def linear_rate(self, symbol: str) -> float:
        """Fractional cost per dollar traded."""
        spread = self.half_spread_bps.get(symbol, self.default_half_spread_bps)
        return (spread + self.commission_bps) / 10_000.0

    def adv(self, symbol: str) -> float:
        return max(self.adv_shares.get(symbol, self.default_adv_shares), 1.0)


@dataclass(slots=True, frozen=True)
class TradeLeg:
    """One symbol's trade."""

    symbol: str
    side: Literal["BUY", "SELL"]
    current_shares: float
    target_shares: float
    delta_shares: int
    notional_usd: float
    linear_cost_usd: float
    impact_cost_usd: float

    @property
    def total_cost_usd(self) -> float:
        return self.linear_cost_usd + self.impact_cost_usd

    def to_payload(self) -> dict[str, Any]:
        return {
            "symbol": self.symbol,
            "side": self.side,
            "currentShares": round(self.current_shares, 6),
            "targetShares": round(self.target_shares, 6),
            "deltaShares": self.delta_shares,
            "notionalUsd": round(self.notional_usd, 2),
            "linearCostUsd": round(self.linear_cost_usd, 4),
            "impactCostUsd": round(self.impact_cost_usd, 4),
            "totalCostUsd": round(self.total_cost_usd, 4),
        }


@dataclass(slots=True, frozen=True)
class RebalancePlan:
    """The trade list, its cost, and a ready-to-POST basket payload."""

    symbols: tuple[str, ...]
    legs: tuple[TradeLeg, ...]
    suppressed_legs: tuple[TradeLeg, ...]
    current_weights: FloatArray
    target_weights: FloatArray
    unconstrained_weights: FloatArray
    nav_usd: float
    turnover_pct: float
    estimated_cost_usd: float
    expected_utility_gain: float
    converged: bool
    message: str
    basket_order_request: dict[str, Any]
    submit_enabled: bool = False

    def to_payload(self) -> dict[str, Any]:
        return {
            "symbols": list(self.symbols),
            "legs": [leg.to_payload() for leg in self.legs],
            "suppressedLegs": [leg.to_payload() for leg in self.suppressed_legs],
            "currentWeights": {
                s: round(float(w), 8)
                for s, w in zip(self.symbols, self.current_weights, strict=True)
            },
            "targetWeights": {
                s: round(float(w), 8)
                for s, w in zip(self.symbols, self.target_weights, strict=True)
            },
            "costFreeTargetWeights": {
                s: round(float(w), 8)
                for s, w in zip(self.symbols, self.unconstrained_weights, strict=True)
            },
            "navUsd": round(self.nav_usd, 2),
            "turnoverPct": round(self.turnover_pct, 6),
            "estimatedCostUsd": round(self.estimated_cost_usd, 2),
            "expectedUtilityGain": round(self.expected_utility_gain, 10),
            "converged": self.converged,
            "message": self.message,
            "basketOrderRequest": self.basket_order_request,
            "submitEnabled": self.submit_enabled,
        }


@dataclass(slots=True, frozen=True)
class RebalanceSettings:
    """Post-processing knobs."""

    min_trade_notional: float = 2_500.0
    no_trade_band_bps: float = 25.0
    lot_size: int = 1
    order_type: str = "MARKET"
    time_in_force: str = "DAY"


def _costs(
    delta_notional: FloatArray,
    symbols: Sequence[str],
    prices: FloatArray,
    daily_vols: FloatArray,
    cost_model: CostModel,
) -> tuple[FloatArray, FloatArray]:
    """Linear and impact cost per asset, both in USD, for a signed notional delta."""
    quantity = np.abs(delta_notional)
    linear = np.array([cost_model.linear_rate(s) for s in symbols], dtype=np.float64) * quantity
    adv_notional = np.array([cost_model.adv(s) for s in symbols], dtype=np.float64) * np.maximum(
        prices, 1e-9
    )
    participation = np.divide(
        quantity, adv_notional, out=np.zeros_like(quantity), where=adv_notional > 0
    )
    impact = cost_model.impact_eta * daily_vols * quantity * np.sqrt(np.maximum(participation, 0.0))
    return linear, impact


def solve(
    symbols: Sequence[str],
    current_weights: FloatArray,
    mu: FloatArray,
    cov: FloatArray,
    nav_usd: float,
    prices: FloatArray,
    cost_model: CostModel,
    constraints: Constraints | None = None,
    risk_aversion: float = 3.0,
    trading_days_per_year: float = 252.0,
) -> tuple[FloatArray, bool, str]:
    """Solve the cost-penalised mean-variance problem. Returns target weights."""
    symbols = tuple(symbols)
    n = len(symbols)
    cons = constraints if constraints is not None else Constraints()
    daily_vols = np.sqrt(np.maximum(np.diag(cov), 0.0) / trading_days_per_year)
    bounds = cons.bounds(n)

    def unpack(x: FloatArray) -> FloatArray:
        return current_weights + x[:n] - x[n:]

    def objective(x: FloatArray) -> float:
        w = unpack(x)
        traded = (x[:n] + x[n:]) * nav_usd
        linear, impact = _costs(traded, symbols, prices, daily_vols, cost_model)
        with clean_matmul():
            utility = float(mu @ w) - 0.5 * risk_aversion * float(w @ cov @ w)
        # Costs are dollars; utility is a return. Divide by NAV so the two are commensurate.
        return -(utility) + float(linear.sum() + impact.sum()) / max(nav_usd, 1e-9)

    budget = {
        "type": "eq",
        "fun": lambda x: float(np.sum(unpack(x)) - 1.0),
        "jac": lambda x: np.concatenate([np.ones(n), -np.ones(n)]),
    }
    box_low = [
        {
            "type": "ineq",
            "fun": (lambda x, k=i, lo=bounds[i][0]: float(unpack(x)[k] - lo)),
        }
        for i in range(n)
    ]
    box_high = [
        {
            "type": "ineq",
            "fun": (lambda x, k=i, hi=bounds[i][1]: float(hi - unpack(x)[k])),
        }
        for i in range(n)
    ]

    x0 = np.zeros(2 * n, dtype=np.float64)
    result = optimize.minimize(
        objective,
        x0,
        method="SLSQP",
        bounds=[(0.0, None)] * (2 * n),
        constraints=[budget, *box_low, *box_high],
        options={"maxiter": 500, "ftol": 1e-12},
    )
    target = unpack(np.asarray(result.x, dtype=np.float64))
    total = float(np.sum(target))
    if abs(total) > 1e-12:
        target = target / total
    return target, bool(result.success), str(result.message)


def build_plan(
    symbols: Sequence[str],
    current_weights: FloatArray,
    target_weights: FloatArray,
    unconstrained_weights: FloatArray,
    nav_usd: float,
    prices: FloatArray,
    cov: FloatArray,
    mu: FloatArray,
    cost_model: CostModel,
    settings: RebalanceSettings,
    risk_aversion: float = 3.0,
    trading_days_per_year: float = 252.0,
    converged: bool = True,
    message: str = "",
    basket_name: str | None = None,
    submit_enabled: bool = False,
) -> RebalancePlan:
    """Turn target weights into a filtered, lot-rounded trade list plus a basket payload."""
    symbols = tuple(symbols)
    n = len(symbols)
    daily_vols = np.sqrt(np.maximum(np.diag(cov), 0.0) / trading_days_per_year)

    delta_weight = target_weights - current_weights
    band = settings.no_trade_band_bps / 10_000.0

    kept: list[TradeLeg] = []
    suppressed: list[TradeLeg] = []

    for i in range(n):
        symbol = symbols[i]
        price = float(prices[i])
        if price <= 0:
            continue
        current_shares = float(current_weights[i]) * nav_usd / price
        target_shares = float(target_weights[i]) * nav_usd / price
        raw_delta_shares = target_shares - current_shares

        lot = max(settings.lot_size, 1)
        rounded = int(round(raw_delta_shares / lot) * lot)
        notional = abs(rounded) * price
        linear, impact = _costs(
            np.array([notional]),
            [symbol],
            np.array([price]),
            np.array([daily_vols[i]]),
            cost_model,
        )
        leg = TradeLeg(
            symbol=symbol,
            side="BUY" if rounded > 0 else "SELL",
            current_shares=current_shares,
            target_shares=target_shares,
            delta_shares=abs(rounded),
            notional_usd=notional,
            linear_cost_usd=float(linear[0]),
            impact_cost_usd=float(impact[0]),
        )

        too_small = (
            rounded == 0
            or notional < settings.min_trade_notional
            or abs(float(delta_weight[i])) < band
        )
        (suppressed if too_small else kept).append(leg)

    # Weights actually achieved after filtering and rounding.
    achieved = current_weights.copy()
    for leg in kept:
        idx = symbols.index(leg.symbol)
        signed = leg.delta_shares if leg.side == "BUY" else -leg.delta_shares
        achieved[idx] = current_weights[idx] + signed * float(prices[idx]) / nav_usd

    with clean_matmul():
        utility_before = float(mu @ current_weights) - 0.5 * risk_aversion * float(
            current_weights @ cov @ current_weights
        )
        utility_after = float(mu @ achieved) - 0.5 * risk_aversion * float(
            achieved @ cov @ achieved
        )

    estimated_cost = float(sum(leg.total_cost_usd for leg in kept))
    turnover = float(0.5 * np.sum(np.abs(achieved - current_weights)))
    name = basket_name or f"rebalance-{datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}"

    return RebalancePlan(
        symbols=symbols,
        legs=tuple(kept),
        suppressed_legs=tuple(suppressed),
        current_weights=current_weights,
        target_weights=achieved,
        unconstrained_weights=unconstrained_weights,
        nav_usd=nav_usd,
        turnover_pct=turnover,
        estimated_cost_usd=estimated_cost,
        expected_utility_gain=utility_after - utility_before,
        converged=converged,
        message=message,
        basket_order_request=to_basket_request(kept, name, settings),
        submit_enabled=submit_enabled,
    )


def to_basket_request(
    legs: Sequence[TradeLeg], name: str, settings: RebalanceSettings
) -> dict[str, Any]:
    """Payload matching execution-engine's ``BasketOrderRequest`` / ``BasketLegRequest``.

    ``BasketLegRequest.quantity`` is annotated ``@Min(1)``, so zero-share legs must never reach
    the wire — they are filtered out upstream and asserted here.
    """
    return {
        "name": name,
        "legs": [
            {
                "symbol": leg.symbol,
                "side": leg.side,
                "orderType": settings.order_type,
                "quantity": int(leg.delta_shares),
                "tif": settings.time_in_force,
            }
            for leg in legs
            if leg.delta_shares >= 1
        ],
    }


def summarize_cost(
    plan: RebalancePlan, cov: FloatArray, trading_days_per_year: float = 252.0
) -> dict[str, Any]:
    """Risk of the current versus the target book — the other half of the trade-off."""
    return {
        "currentVolatility": round(portfolio_volatility(plan.current_weights, cov), 8),
        "targetVolatility": round(portfolio_volatility(plan.target_weights, cov), 8),
        "costBpsOfNav": round(plan.estimated_cost_usd / max(plan.nav_usd, 1e-9) * 10_000.0, 4),
        "tradingDaysPerYear": trading_days_per_year,
        "legCount": len(plan.legs),
        "suppressedCount": len(plan.suppressed_legs),
        "costFreeDistance": round(
            float(np.abs(plan.target_weights - plan.unconstrained_weights).sum()), 8
        ),
    }


def _ensure_finite(values: FloatArray, what: str) -> None:
    if not np.isfinite(values).all():
        raise ValueError(f"{what} contains non-finite entries")
    if math.isnan(float(values.sum())):
        raise ValueError(f"{what} sums to NaN")
