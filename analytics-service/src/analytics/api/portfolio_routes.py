"""REST surface for portfolio construction and the risk engine (roadmap 4.6.1).

Kept in its own router so ``api/app.py`` stays readable. Everything here is mounted under
``/v1/analytics/`` and reaches the outside world through the API gateway's existing
``/api/analytics/** -> /v1/analytics/**`` rewrite — no gateway change was needed for this
feature.

Conventions follow the rest of the analytics service: **snake_case request bodies**
(as ``AxePublishRequest`` already does) and **camelCase responses** (as every existing analytics
endpoint already returns). The asymmetry is pre-existing; changing it was out of scope.

Handlers are synchronous ``def`` rather than ``async def`` on purpose. FastAPI runs sync handlers
in a threadpool, so a 10,000-path Monte Carlo or an SLSQP solve cannot block the event loop —
which an ``async def`` doing the same numpy work would.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Annotated, Any, Literal

import numpy as np
from fastapi import APIRouter, Body, HTTPException, Query
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

from analytics.portfolio import black_litterman as bl
from analytics.portfolio import factors as factor_model
from analytics.portfolio import rebalance as rebalancer
from analytics.portfolio.basket_client import BasketSubmissionError
from analytics.portfolio.covariance import CovarianceEstimate, from_supplied
from analytics.portfolio.optimizers import (
    Constraints,
    Objective,
    efficient_frontier,
    optimize_portfolio,
)
from analytics.risk import report as risk_report
from analytics.risk.stress import apply_scenarios

if TYPE_CHECKING:
    from analytics.config import Settings
    from analytics.consumer.market_data import MarketDataCache
    from analytics.numeric import FloatArray
    from analytics.portfolio.basket_client import BasketClient
    from analytics.portfolio.reference import PortfolioReference
    from analytics.portfolio.service import CovarianceService
    from analytics.portfolio.state import PortfolioSnapshot, PortfolioState
    from analytics.risk.engine import RiskEngine


# ----------------------------------------------------------------- request models


class PositionInput(BaseModel):
    symbol: str = Field(min_length=1, max_length=16)
    quantity: float
    price: float | None = Field(default=None, gt=0)


class ConstraintsInput(BaseModel):
    min_weight: float = Field(default=0.0, ge=-1.0, le=1.0)
    max_weight: float = Field(default=1.0, gt=0.0, le=1.0)
    allow_shorts: bool = False
    max_sector_weight: dict[str, float] = Field(default_factory=dict)
    risk_budget: dict[str, float] | None = None

    def to_domain(self) -> Constraints:
        if self.min_weight > self.max_weight:
            raise HTTPException(400, "min_weight cannot exceed max_weight")
        return Constraints(
            min_weight=self.min_weight,
            max_weight=self.max_weight,
            allow_shorts=self.allow_shorts,
            max_sector_weight=dict(self.max_sector_weight),
            risk_budget=dict(self.risk_budget) if self.risk_budget else None,
        )


class ViewInput(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    pick: dict[str, float] = Field(min_length=1)
    expected_return: float
    confidence: float = Field(default=1.0, gt=0.0, le=1000.0)


class UniverseRequest(BaseModel):
    """Fields every portfolio request shares."""

    symbols: list[str] | None = None
    positions: list[PositionInput] | None = None
    covariance: list[list[float]] | None = None


class OptimizeRequest(UniverseRequest):
    objective: Literal[
        "MEAN_VARIANCE",
        "MIN_VARIANCE",
        "MAX_SHARPE",
        "RISK_PARITY",
        "EQUAL_WEIGHT",
        "INVERSE_VOL",
    ] = "MIN_VARIANCE"
    constraints: ConstraintsInput = Field(default_factory=ConstraintsInput)
    risk_aversion: float | None = Field(default=None, gt=0.0, le=1000.0)
    expected_returns: dict[str, float] | None = None


class FrontierRequest(UniverseRequest):
    points: int = Field(default=25, ge=2, le=200)
    constraints: ConstraintsInput = Field(default_factory=ConstraintsInput)
    expected_returns: dict[str, float] | None = None


class BlackLittermanRequest(UniverseRequest):
    views: list[ViewInput] = Field(default_factory=list)
    market_weights: dict[str, float] | None = None
    tau: float | None = Field(default=None, gt=0.0, le=1.0)
    risk_aversion: float | None = Field(default=None, gt=0.0, le=1000.0)
    use_woodbury: bool = True


class FactorsRequest(UniverseRequest):
    weights: dict[str, float] | None = None


class RebalanceRequest(UniverseRequest):
    objective: Literal[
        "MEAN_VARIANCE",
        "MIN_VARIANCE",
        "MAX_SHARPE",
        "RISK_PARITY",
        "EQUAL_WEIGHT",
        "INVERSE_VOL",
    ] = "RISK_PARITY"
    constraints: ConstraintsInput = Field(default_factory=ConstraintsInput)
    risk_aversion: float | None = Field(default=None, gt=0.0, le=1000.0)
    expected_returns: dict[str, float] | None = None
    target_weights: dict[str, float] | None = None
    min_trade_notional: float | None = Field(default=None, ge=0.0)
    no_trade_band_bps: float | None = Field(default=None, ge=0.0, le=10_000.0)
    lot_size: int | None = Field(default=None, ge=1, le=10_000)
    order_type: Literal["MARKET", "LIMIT"] = "MARKET"
    basket_name: str | None = Field(default=None, max_length=120)


class VarRequest(UniverseRequest):
    confidence: float | None = Field(default=None, gt=0.5, lt=1.0)
    horizon_days: float | None = Field(default=None, gt=0.0, le=30.0)
    simulations: int | None = Field(default=None, ge=100, le=200_000)
    distribution: Literal["normal", "t"] | None = None
    include_scenarios: bool = True


class StressRequest(UniverseRequest):
    scenario_names: list[str] | None = None


# ----------------------------------------------------------------------- router


def build_portfolio_router(
    settings: Settings,
    reference: PortfolioReference,
    portfolio_state: PortfolioState | None,
    covariance_service: CovarianceService | None,
    risk_engine: RiskEngine | None,
    market_cache: MarketDataCache | None = None,
    basket_client: BasketClient | None = None,
) -> APIRouter:
    router = APIRouter(prefix="/v1/analytics", tags=["portfolio", "risk"])

    # ------------------------------------------------------------- helpers

    def need_state() -> PortfolioState:
        if portfolio_state is None:
            raise HTTPException(503, "portfolio state is not wired")
        return portfolio_state

    def need_covariance() -> CovarianceService:
        if covariance_service is None:
            raise HTTPException(503, "covariance service is not wired")
        return covariance_service

    def need_risk() -> RiskEngine:
        if risk_engine is None:
            raise HTTPException(503, "risk engine is not wired")
        return risk_engine

    def resolve_book(request: UniverseRequest) -> PortfolioSnapshot:
        state = need_state()
        if request.positions:
            try:
                return state.from_explicit([p.model_dump() for p in request.positions])
            except ValueError as exc:
                raise HTTPException(400, str(exc)) from exc
        return state.snapshot()

    def resolve_symbols(request: UniverseRequest) -> tuple[str, ...]:
        """Universe for an optimisation: explicit, else configured universe plus what is held.

        Defaulting to the *configured universe* rather than to the current book matters: an
        optimiser restricted to what you already hold can never tell you to buy something new,
        which is most of the point. Held symbols outside the configured universe are unioned in
        so an existing position is never silently dropped from the risk picture.
        """
        if request.symbols:
            return tuple(dict.fromkeys(s.strip().upper() for s in request.symbols if s.strip()))
        if request.positions:
            return tuple(
                dict.fromkeys(p.symbol.strip().upper() for p in request.positions if p.symbol)
            )
        universe = need_covariance().universe()
        held = need_state().snapshot().symbols
        combined = tuple(dict.fromkeys((*universe, *held)))
        if not combined:
            raise HTTPException(
                400, "no symbols: supply `symbols`, `positions`, or configure a universe"
            )
        return combined

    def resolve_covariance(
        request: UniverseRequest, symbols: tuple[str, ...]
    ) -> CovarianceEstimate:
        if request.covariance is not None:
            matrix = np.asarray(request.covariance, dtype=np.float64)
            if matrix.shape != (len(symbols), len(symbols)):
                raise HTTPException(
                    400,
                    f"covariance must be {len(symbols)}x{len(symbols)} to match the "
                    f"{len(symbols)} resolved symbols",
                )
            try:
                return from_supplied(symbols, matrix, settings.trading_days_per_year)
            except ValueError as exc:
                raise HTTPException(400, str(exc)) from exc
        try:
            return need_covariance().current(symbols)
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc

    def resolve_mu(
        explicit: dict[str, float] | None,
        symbols: tuple[str, ...],
        cov: FloatArray,
    ) -> tuple[FloatArray, str]:
        """Expected returns: explicit if given, otherwise the BL equilibrium.

        Defaulting to equilibrium rather than to zeros or to sample means is the whole point of
        section 3.4: there is no alpha model feeding ``mu`` in this system, and sample means
        would produce corner solutions.
        """
        if explicit:
            unknown = [s for s in explicit if s not in symbols]
            if unknown:
                raise HTTPException(
                    400, f"expected_returns references unknown symbols: {', '.join(unknown)}"
                )
            return (
                np.array([float(explicit.get(s, 0.0)) for s in symbols], dtype=np.float64),
                "supplied",
            )
        weights = reference.market_weights(symbols)
        pi = bl.equilibrium_returns(cov, weights, settings.bl_market_risk_aversion)
        return pi, "black-litterman-equilibrium"

    def live_returns(symbols: tuple[str, ...]) -> FloatArray | None:
        if market_cache is None:
            return None
        matrix, live = market_cache.returns_matrix(
            symbols,
            bar_seconds=settings.returns_bar_seconds,
            max_bars=settings.returns_max_bars,
            clock=settings.returns_clock,
        )
        if matrix.size == 0 or tuple(live) != symbols:
            return None
        return matrix

    # -------------------------------------------------------------- routes

    @router.get("/portfolio/state")
    def get_state() -> dict[str, Any]:
        return need_state().snapshot().to_payload()

    @router.get("/portfolio/covariance")
    def get_covariance(
        symbols: Annotated[str | None, Query(description="comma-separated subset")] = None,
    ) -> dict[str, Any]:
        service = need_covariance()
        requested = (
            tuple(s.strip().upper() for s in symbols.split(",") if s.strip())
            if symbols
            else service.universe()
        )
        if not requested:
            raise HTTPException(400, "no symbols configured and no market data seen yet")
        try:
            return service.current(requested).to_payload()
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc

    @router.post("/portfolio/covariance")
    def post_covariance(request: Annotated[UniverseRequest, Body()]) -> dict[str, Any]:
        symbols = resolve_symbols(request)
        return resolve_covariance(request, symbols).to_payload()

    @router.post("/portfolio/optimize")
    def post_optimize(request: Annotated[OptimizeRequest, Body()]) -> dict[str, Any]:
        symbols = resolve_symbols(request)
        estimate = resolve_covariance(request, symbols)
        mu, mu_source = resolve_mu(request.expected_returns, symbols, estimate.covariance)
        constraints = request.constraints.to_domain()
        aversion = (
            request.risk_aversion
            if request.risk_aversion is not None
            else settings.optimizer_risk_aversion
        )
        try:
            result = optimize_portfolio(
                Objective(request.objective),
                symbols,
                estimate.covariance,
                mu=mu,
                constraints=constraints,
                risk_aversion=aversion,
                sectors=reference.sectors(symbols),
            )
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc
        payload = result.to_payload()
        payload["expectedReturnSource"] = mu_source
        payload["riskAversion"] = aversion
        payload["diagnostics"] = estimate.diagnostics()
        return payload

    @router.post("/portfolio/efficient-frontier")
    def post_frontier(request: Annotated[FrontierRequest, Body()]) -> dict[str, Any]:
        symbols = resolve_symbols(request)
        estimate = resolve_covariance(request, symbols)
        mu, mu_source = resolve_mu(request.expected_returns, symbols, estimate.covariance)
        try:
            points = efficient_frontier(
                symbols,
                mu,
                estimate.covariance,
                constraints=request.constraints.to_domain(),
                points=request.points,
                sectors=reference.sectors(symbols),
            )
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc
        return {
            "symbols": list(symbols),
            "points": [p.to_payload(symbols) for p in points],
            "expectedReturnSource": mu_source,
            "diagnostics": estimate.diagnostics(),
        }

    @router.post("/portfolio/black-litterman")
    def post_black_litterman(request: Annotated[BlackLittermanRequest, Body()]) -> dict[str, Any]:
        symbols = resolve_symbols(request)
        estimate = resolve_covariance(request, symbols)
        if request.market_weights:
            unknown = [s for s in request.market_weights if s not in symbols]
            if unknown:
                raise HTTPException(
                    400, f"market_weights references unknown symbols: {', '.join(unknown)}"
                )
            raw = np.array(
                [float(request.market_weights.get(s, 0.0)) for s in symbols], dtype=np.float64
            )
            total = float(raw.sum())
            if total <= 0:
                raise HTTPException(400, "market_weights must sum to a positive number")
            weights = raw / total
        else:
            weights = reference.market_weights(symbols)

        views = [
            bl.View(
                name=v.name,
                pick=dict(v.pick),
                expected_return=v.expected_return,
                confidence=v.confidence,
            )
            for v in request.views
        ]
        try:
            result = bl.run(
                symbols,
                estimate.covariance,
                weights,
                views=views,
                tau=request.tau if request.tau is not None else settings.bl_tau,
                risk_aversion=(
                    request.risk_aversion
                    if request.risk_aversion is not None
                    else settings.bl_market_risk_aversion
                ),
                use_woodbury=request.use_woodbury,
            )
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc
        payload = result.to_payload()
        payload["diagnostics"] = estimate.diagnostics()
        return payload

    @router.get("/portfolio/factors")
    def get_factors() -> dict[str, Any]:
        return post_factors(FactorsRequest())

    @router.post("/portfolio/factors")
    def post_factors(request: Annotated[FactorsRequest, Body()]) -> dict[str, Any]:
        symbols = resolve_symbols(request)
        estimate = resolve_covariance(request, symbols)

        if request.weights:
            unknown = [s for s in request.weights if s not in symbols]
            if unknown:
                raise HTTPException(
                    400, f"weights references unknown symbols: {', '.join(unknown)}"
                )
            weights = np.array(
                [float(request.weights.get(s, 0.0)) for s in symbols], dtype=np.float64
            )
        else:
            book = resolve_book(request)
            if book.symbols and set(book.symbols) == set(symbols):
                order = {s: i for i, s in enumerate(book.symbols)}
                raw = book.weights()
                weights = np.array([raw[order[s]] for s in symbols], dtype=np.float64)
            else:
                weights = np.full(len(symbols), 1.0 / len(symbols), dtype=np.float64)

        decomposition = factor_model.decompose(
            symbols,
            weights,
            estimate.covariance,
            estimate.correlation,
            reference,
            returns=live_returns(symbols),
        )
        payload = decomposition.to_payload()
        payload["weights"] = {s: round(float(w), 8) for s, w in zip(symbols, weights, strict=True)}
        payload["diagnostics"] = estimate.diagnostics()
        return payload

    @router.post("/portfolio/rebalance")
    def post_rebalance(request: Annotated[RebalanceRequest, Body()]) -> dict[str, Any]:
        return _rebalance(request)

    @router.post("/portfolio/rebalance/submit")
    def post_rebalance_submit(request: Annotated[RebalanceRequest, Body()]) -> dict[str, Any]:
        if basket_client is None or not basket_client.enabled:
            raise HTTPException(
                503,
                "basket submission is disabled; set ANALYTICS_REBALANCE_SUBMIT_ENABLED=true to "
                "allow the analytics service to send live orders",
            )
        plan = _rebalance(request)
        try:
            submission = basket_client.submit(plan["basketOrderRequest"])
        except BasketSubmissionError as exc:
            raise HTTPException(exc.status_code or 502, str(exc)) from exc
        return {"plan": plan, "submission": submission}

    def _rebalance(request: RebalanceRequest) -> dict[str, Any]:
        book = resolve_book(request)
        symbols = resolve_symbols(request)
        if not symbols:
            raise HTTPException(400, "no symbols to rebalance")

        estimate = resolve_covariance(request, symbols)
        mu, mu_source = resolve_mu(request.expected_returns, symbols, estimate.covariance)
        constraints = request.constraints.to_domain()
        aversion = (
            request.risk_aversion
            if request.risk_aversion is not None
            else settings.optimizer_risk_aversion
        )

        # Current weights and prices, in the resolved symbol order.
        book_weights = {s: float(w) for s, w in zip(book.symbols, book.weights(), strict=True)}
        book_prices = book.prices()
        current = np.array([book_weights.get(s, 0.0) for s in symbols], dtype=np.float64)
        prices = np.array(
            [
                book_prices.get(s)
                or (market_cache.latest(s) if market_cache is not None else None)
                or 0.0
                for s in symbols
            ],
            dtype=np.float64,
        )
        missing = [s for i, s in enumerate(symbols) if prices[i] <= 0]
        if missing:
            raise HTTPException(
                400,
                "no price available for "
                + ", ".join(missing)
                + "; supply them via `positions[].price`",
            )

        if request.target_weights:
            unknown = [s for s in request.target_weights if s not in symbols]
            if unknown:
                raise HTTPException(
                    400, f"target_weights references unknown symbols: {', '.join(unknown)}"
                )
            raw = np.array(
                [float(request.target_weights.get(s, 0.0)) for s in symbols], dtype=np.float64
            )
            total = float(raw.sum())
            if abs(total) < 1e-12:
                raise HTTPException(400, "target_weights must sum to a non-zero number")
            target = raw / total
            cost_free = target.copy()
            converged, message = True, "target weights supplied"
        else:
            try:
                cost_free = optimize_portfolio(
                    Objective(request.objective),
                    symbols,
                    estimate.covariance,
                    mu=mu,
                    constraints=constraints,
                    risk_aversion=aversion,
                    sectors=reference.sectors(symbols),
                ).weights
            except ValueError as exc:
                raise HTTPException(400, str(exc)) from exc
            cost_model = rebalancer.CostModel.from_reference(
                reference, eta=settings.rebalance_impact_eta
            )
            target, converged, message = rebalancer.solve(
                symbols,
                current,
                mu,
                estimate.covariance,
                book.nav_usd,
                prices,
                cost_model,
                constraints=constraints,
                risk_aversion=aversion,
                trading_days_per_year=settings.trading_days_per_year,
            )

        plan_settings = rebalancer.RebalanceSettings(
            min_trade_notional=(
                request.min_trade_notional
                if request.min_trade_notional is not None
                else settings.rebalance_min_trade_notional
            ),
            no_trade_band_bps=(
                request.no_trade_band_bps
                if request.no_trade_band_bps is not None
                else settings.rebalance_no_trade_band_bps
            ),
            lot_size=request.lot_size
            if request.lot_size is not None
            else settings.rebalance_lot_size,
            order_type=request.order_type,
        )
        plan = rebalancer.build_plan(
            symbols,
            current,
            target,
            cost_free,
            book.nav_usd,
            prices,
            estimate.covariance,
            mu,
            rebalancer.CostModel.from_reference(reference, eta=settings.rebalance_impact_eta),
            plan_settings,
            risk_aversion=aversion,
            trading_days_per_year=settings.trading_days_per_year,
            converged=converged,
            message=message,
            basket_name=request.basket_name,
            submit_enabled=bool(basket_client is not None and basket_client.enabled),
        )
        payload = plan.to_payload()
        payload["expectedReturnSource"] = mu_source
        payload["summary"] = rebalancer.summarize_cost(
            plan, estimate.covariance, settings.trading_days_per_year
        )
        payload["diagnostics"] = estimate.diagnostics()
        return payload

    # ---------------------------------------------------------------- risk

    @router.get("/risk/var")
    def get_var() -> dict[str, Any]:
        return need_risk().evaluate().to_payload()

    @router.post("/risk/var")
    def post_var(request: Annotated[VarRequest, Body()]) -> dict[str, Any]:
        engine = need_risk()
        book = resolve_book(request)
        try:
            return engine.evaluate(
                snapshot=book,
                confidence=request.confidence,
                horizon_days=request.horizon_days,
                simulations=request.simulations,
                distribution=request.distribution,
                include_scenarios=request.include_scenarios,
            ).to_payload()
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc

    @router.get("/risk/components")
    def get_components() -> dict[str, Any]:
        report = need_risk().evaluate()
        return {
            "asOf": report.as_of.isoformat(),
            "confidence": report.confidence,
            "horizonDays": report.horizon_days,
            "varUsd": round(report.parametric.var_usd, 2),
            "sumOfAbsolutesVarUsd": round(report.sum_of_absolutes_var_usd, 2),
            "diversificationRatio": round(report.diversification_ratio, 4),
            "rows": [row.to_payload() for row in report.components],
        }

    @router.get("/risk/stress")
    def get_stress() -> dict[str, Any]:
        return post_stress(StressRequest())

    @router.post("/risk/stress")
    def post_stress(request: Annotated[StressRequest, Body()]) -> dict[str, Any]:
        engine = need_risk()
        book = resolve_book(request)
        scenarios = engine.scenarios
        if request.scenario_names:
            wanted = {n.strip().upper() for n in request.scenario_names}
            scenarios = tuple(s for s in scenarios if s.name.upper() in wanted)
            if not scenarios:
                raise HTTPException(404, f"no scenarios named: {', '.join(sorted(wanted))}")
        estimate = None
        if book.symbols:
            estimate = resolve_covariance(request, book.symbols)
        results = apply_scenarios(
            scenarios, book, reference, estimate, loss_limit_usd=settings.stress_loss_limit_usd
        )
        return {
            "navUsd": round(book.nav_usd, 2),
            "lossLimitUsd": settings.stress_loss_limit_usd,
            "scenarios": [r.to_payload() for r in results],
        }

    @router.get("/risk/report", response_class=HTMLResponse)
    def get_report() -> HTMLResponse:
        engine = need_risk()
        report = engine.evaluate()
        correlation: list[list[float]] | None = None
        if report.symbols and covariance_service is not None:
            try:
                estimate = covariance_service.current(report.symbols)
                correlation = [[float(c) for c in row] for row in estimate.correlation]
            except ValueError:
                correlation = None
        return HTMLResponse(content=risk_report.render(report, correlation))

    return router
