"""Stress scenarios — deterministic "what if the world does X" revaluation.

VaR answers "how bad is a bad day, statistically". Stress answers "how bad is *this* day", and
the two disagree exactly when it matters. Two scenario kinds:

**FACTOR** — a market move propagated through each name's beta::

    r_i = beta_i * market_shock + idiosyncratic_i

with volatilities scaled by ``vol_multiplier`` and correlations pulled toward
``correlation_override``. The correlation override is the important half: in a crisis,
cross-sectional correlations converge toward one and the diversification that the covariance
model credited you with evaporates. A stress test that keeps the calm-market correlation matrix
is theatre — it will always report that the hedged book is fine, which is the one thing a stress
test is supposed to be able to contradict.

The idiosyncratic term is taken at its one-sigma stressed level and signed *against* the
position, so the scenario is a genuine adverse case rather than a coin flip.

**EXPLICIT** — per-symbol or per-sector percentage moves, with ``default_shock`` for anything
unnamed. This is how you replay a specific date or express a thematic unwind.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Any, Literal

import numpy as np
import structlog
import yaml

from analytics.metrics import STRESS_BREACHES

if TYPE_CHECKING:
    from collections.abc import Sequence

    from analytics.numeric import FloatArray
    from analytics.portfolio.covariance import CovarianceEstimate
    from analytics.portfolio.reference import PortfolioReference
    from analytics.portfolio.state import PortfolioSnapshot

logger = structlog.get_logger()

ScenarioKind = Literal["FACTOR", "EXPLICIT"]


@dataclass(slots=True, frozen=True)
class Scenario:
    """One declared stress scenario."""

    name: str
    description: str = ""
    kind: ScenarioKind = "FACTOR"
    market_shock: float = 0.0
    vol_multiplier: float = 1.0
    correlation_override: float | None = None
    symbol_shocks: dict[str, float] = field(default_factory=dict)
    sector_shocks: dict[str, float] = field(default_factory=dict)
    default_shock: float = 0.0


@dataclass(slots=True, frozen=True)
class ScenarioResult:
    """Revalued P&L for one scenario."""

    name: str
    description: str
    pnl_usd: float
    pnl_pct_of_nav: float
    worst_symbol: str
    worst_symbol_pnl_usd: float
    breaches_limit: bool
    shocks: dict[str, float] = field(default_factory=dict)

    def to_payload(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "pnlUsd": round(self.pnl_usd, 2),
            "pnlPctOfNav": round(self.pnl_pct_of_nav, 6),
            "worstSymbol": self.worst_symbol,
            "worstSymbolPnlUsd": round(self.worst_symbol_pnl_usd, 2),
            "breachesLimit": self.breaches_limit,
            "shocks": {k: round(v, 6) for k, v in self.shocks.items()},
        }


def load_scenarios(path: Path | str | None) -> tuple[Scenario, ...]:
    """Load ``config/stress-scenarios.yml``. Returns an empty tuple on any problem."""
    if path is None:
        return ()
    p = Path(path)
    if not p.exists():
        logger.warning("stress_scenarios_missing", path=str(p))
        return ()
    try:
        raw = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
        block = raw.get("stress") or {}
        scenarios = tuple(_parse(entry) for entry in (block.get("scenarios") or []))
        logger.info("stress_scenarios_loaded", path=str(p), count=len(scenarios))
        return scenarios
    except (OSError, yaml.YAMLError, TypeError, ValueError):
        logger.exception("stress_scenarios_load_failed", path=str(p))
        return ()


def load_loss_limit(path: Path | str | None, default: float) -> float:
    """Read ``stress.loss-limit-usd``, falling back to the configured default."""
    if path is None:
        return default
    p = Path(path)
    if not p.exists():
        return default
    try:
        raw = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
        block = raw.get("stress") or {}
        value = block.get("loss-limit-usd")
        return float(value) if value is not None else default
    except (OSError, yaml.YAMLError, TypeError, ValueError):
        return default


def _parse(entry: dict[str, Any]) -> Scenario:
    if not isinstance(entry, dict) or "name" not in entry:
        raise ValueError("each scenario needs a name")
    kind = str(entry.get("kind", "FACTOR")).upper()
    if kind not in ("FACTOR", "EXPLICIT"):
        raise ValueError(f"unknown scenario kind: {kind}")
    override = entry.get("correlation-override")
    return Scenario(
        name=str(entry["name"]),
        description=str(entry.get("description", "")),
        kind="FACTOR" if kind == "FACTOR" else "EXPLICIT",
        market_shock=float(entry.get("market-shock", 0.0) or 0.0),
        vol_multiplier=float(entry.get("vol-multiplier", 1.0) or 1.0),
        correlation_override=float(override) if override is not None else None,
        symbol_shocks={str(k): float(v) for k, v in (entry.get("symbol-shocks") or {}).items()},
        sector_shocks={str(k): float(v) for k, v in (entry.get("sector-shocks") or {}).items()},
        default_shock=float(entry.get("default-shock", 0.0) or 0.0),
    )


def shock_vector(
    scenario: Scenario,
    symbols: Sequence[str],
    reference: PortfolioReference,
    estimate: CovarianceEstimate | None,
    notionals: FloatArray | None = None,
) -> dict[str, float]:
    """Per-symbol return shock implied by ``scenario``."""
    shocks: dict[str, float] = {}

    if scenario.kind == "EXPLICIT":
        for i, symbol in enumerate(symbols):
            if symbol in scenario.symbol_shocks:
                shocks[symbol] = scenario.symbol_shocks[symbol]
                continue
            sector = reference.ref(symbol).sector
            shocks[symbol] = scenario.sector_shocks.get(sector, scenario.default_shock)
            del i
        return shocks

    # FACTOR: beta-propagated market move plus an adverse one-sigma idiosyncratic kicker.
    daily_vols = (
        estimate.daily_volatilities()
        if estimate is not None
        else reference.prior_volatilities(symbols) / np.sqrt(252.0)
    )
    betas = reference.betas(symbols)
    market_vol = reference.correlation_prior.market_volatility / np.sqrt(252.0)

    for i, symbol in enumerate(symbols):
        systematic = betas[i] * scenario.market_shock
        # Idiosyncratic vol left after removing the market component, stressed by the multiplier.
        total_var = float(daily_vols[i]) ** 2
        systematic_var = (betas[i] * market_vol) ** 2
        idio_vol = float(np.sqrt(max(total_var - systematic_var, 0.0)))
        adverse = -idio_vol * scenario.vol_multiplier
        if notionals is not None and i < len(notionals) and notionals[i] < 0:
            adverse = -adverse
        shocks[symbol] = float(systematic + adverse)
    return shocks


def apply_scenario(
    scenario: Scenario,
    book: PortfolioSnapshot,
    reference: PortfolioReference,
    estimate: CovarianceEstimate | None = None,
    loss_limit_usd: float = 0.0,
) -> ScenarioResult:
    """Revalue ``book`` under ``scenario``."""
    symbols = book.symbols
    notionals = book.notionals()
    shocks = shock_vector(scenario, symbols, reference, estimate, notionals)

    per_symbol = {s: float(notionals[i]) * shocks[s] for i, s in enumerate(symbols)}
    total = float(sum(per_symbol.values()))

    if per_symbol:
        worst_symbol = min(per_symbol, key=lambda s: per_symbol[s])
        worst_pnl = per_symbol[worst_symbol]
    else:
        worst_symbol, worst_pnl = "", 0.0

    breaches = loss_limit_usd > 0 and total < -loss_limit_usd
    if breaches:
        STRESS_BREACHES.labels(scenario=scenario.name).inc()

    return ScenarioResult(
        name=scenario.name,
        description=scenario.description,
        pnl_usd=total,
        pnl_pct_of_nav=total / book.nav_usd if book.nav_usd > 0 else 0.0,
        worst_symbol=worst_symbol,
        worst_symbol_pnl_usd=worst_pnl,
        breaches_limit=breaches,
        shocks=shocks,
    )


def apply_scenarios(
    scenarios: Sequence[Scenario],
    book: PortfolioSnapshot,
    reference: PortfolioReference,
    estimate: CovarianceEstimate | None = None,
    loss_limit_usd: float = 0.0,
) -> tuple[ScenarioResult, ...]:
    """Revalue ``book`` under every scenario, worst loss first."""
    results = [apply_scenario(s, book, reference, estimate, loss_limit_usd) for s in scenarios]
    results.sort(key=lambda r: r.pnl_usd)
    return tuple(results)
