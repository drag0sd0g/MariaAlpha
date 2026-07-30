"""Portfolio reference data — ``config/portfolio.yml`` loader and the correlation prior.

Two jobs:

1. **Reference data** — sector, beta, ADV, market cap, prior annualised volatility and
   half-spread per symbol. Mirrors ``execution-engine.risk.reference-data`` so the two services
   agree on what a symbol is; adds market cap, which analytics needs for the SIZE factor and for
   the Black-Litterman market portfolio.

2. **The correlation prior** — a *block* correlation model rather than a hand-maintained matrix.
   Nobody keeps a 50x50 matrix current by hand, and the estimator needs a target that is always
   available and always positive-definite:

   ::

       rho_ij = (1 - b) * block_ij + b * min(1, beta_i beta_j sigma_m^2 / (sigma_i sigma_j))

   where ``block_ij`` is ``intra-sector`` when i and j share a sector and ``inter-sector``
   otherwise, and the second term is the correlation a single-factor market model implies. Both
   terms are valid correlation structures — the block matrix is a convex combination of ``I`` and
   ``11'`` restricted to blocks, and the beta term is rank-1-plus-diagonal — so the blend is PSD
   for ``b`` in [0, 1] and ``block`` in [0, 1).

The prior does double duty: it is the whole model on a cold start, and it is the Ledoit-Wolf
shrinkage target once live returns exist.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Any

import numpy as np
import structlog
import yaml

if TYPE_CHECKING:
    from collections.abc import Sequence

    from analytics.numeric import FloatArray

logger = structlog.get_logger()

# Used when a symbol is absent from config/portfolio.yml. Deliberately vol-heavy: an unknown
# name should not look safe.
DEFAULT_SECTOR = "UNKNOWN"
DEFAULT_BETA = 1.0
DEFAULT_ADV = 1_000_000.0
DEFAULT_MARKET_CAP = 1_000_000_000.0
DEFAULT_ANNUALIZED_VOLATILITY = 0.35
DEFAULT_HALF_SPREAD_BPS = 2.0


@dataclass(slots=True, frozen=True)
class SymbolRef:
    """Per-symbol reference data."""

    symbol: str
    sector: str = DEFAULT_SECTOR
    beta: float = DEFAULT_BETA
    adv: float = DEFAULT_ADV
    market_cap: float = DEFAULT_MARKET_CAP
    annualized_volatility: float = DEFAULT_ANNUALIZED_VOLATILITY
    half_spread_bps: float = DEFAULT_HALF_SPREAD_BPS


@dataclass(slots=True, frozen=True)
class CorrelationPrior:
    """Block + beta-implied correlation model."""

    intra_sector: float = 0.65
    inter_sector: float = 0.35
    market_beta_blend: float = 0.5
    market_volatility: float = 0.18


@dataclass(slots=True, frozen=True)
class CostConfig:
    """Transaction-cost parameters for the rebalancer."""

    commission_bps: float = 0.5
    default_half_spread_bps: float = 1.0
    impact_eta: float = 1.0


@dataclass(slots=True)
class PortfolioReference:
    """Loaded ``config/portfolio.yml``, plus the derived correlation prior."""

    universe: tuple[str, ...] = ()
    symbols: dict[str, SymbolRef] = field(default_factory=dict)
    risk_aversion: float = 3.0
    market_risk_aversion: float = 2.5
    tau: float = 0.05
    correlation_prior: CorrelationPrior = field(default_factory=CorrelationPrior)
    cost: CostConfig = field(default_factory=CostConfig)
    source_path: str | None = None

    # ---------------------------------------------------------------- loading

    @classmethod
    def load(cls, path: Path | str | None) -> PortfolioReference:
        """Load from YAML, falling back to a hard-coded default on any problem.

        A missing or malformed reference file must not stop the service booting — the rest of
        the analytics surface (toxicity, PnL attribution, axes) does not depend on it. The
        fallback is logged loudly and reported through ``source_path=None`` so responses can say
        where their numbers came from.
        """
        if path is None:
            return cls(universe=(), symbols={}, source_path=None)
        p = Path(path)
        if not p.exists():
            logger.warning("portfolio_reference_missing", path=str(p))
            return cls(source_path=None)
        try:
            raw = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
            return cls._from_mapping(raw, str(p))
        except (OSError, yaml.YAMLError, TypeError, ValueError):
            logger.exception("portfolio_reference_load_failed", path=str(p))
            return cls(source_path=None)

    @classmethod
    def _from_mapping(cls, raw: dict[str, Any], source_path: str) -> PortfolioReference:
        block = raw.get("portfolio") or {}
        if not isinstance(block, dict):
            raise TypeError("portfolio: must be a mapping")

        symbols: dict[str, SymbolRef] = {}
        for entry in block.get("symbols") or []:
            if not isinstance(entry, dict) or "symbol" not in entry:
                continue
            symbol = str(entry["symbol"])
            symbols[symbol] = SymbolRef(
                symbol=symbol,
                sector=str(entry.get("sector", DEFAULT_SECTOR)),
                beta=float(entry.get("beta", DEFAULT_BETA)),
                adv=float(entry.get("adv", DEFAULT_ADV)),
                market_cap=float(entry.get("market-cap", DEFAULT_MARKET_CAP)),
                annualized_volatility=float(
                    entry.get("annualized-volatility", DEFAULT_ANNUALIZED_VOLATILITY)
                ),
                half_spread_bps=float(entry.get("half-spread-bps", DEFAULT_HALF_SPREAD_BPS)),
            )

        universe = tuple(str(s) for s in (block.get("universe") or symbols.keys()))

        prior_raw = block.get("correlation-prior") or {}
        prior = CorrelationPrior(
            intra_sector=float(prior_raw.get("intra-sector", 0.65)),
            inter_sector=float(prior_raw.get("inter-sector", 0.35)),
            market_beta_blend=float(prior_raw.get("market-beta-blend", 0.5)),
            market_volatility=float(prior_raw.get("market-volatility", 0.18)),
        )

        cost_raw = block.get("cost") or {}
        cost = CostConfig(
            commission_bps=float(cost_raw.get("commission-bps", 0.5)),
            default_half_spread_bps=float(cost_raw.get("default-half-spread-bps", 1.0)),
            impact_eta=float(cost_raw.get("impact-eta", 1.0)),
        )

        ref = cls(
            universe=universe,
            symbols=symbols,
            risk_aversion=float(block.get("risk-aversion", 3.0)),
            market_risk_aversion=float(block.get("market-risk-aversion", 2.5)),
            tau=float(block.get("tau", 0.05)),
            correlation_prior=prior,
            cost=cost,
            source_path=source_path,
        )
        logger.info(
            "portfolio_reference_loaded",
            path=source_path,
            symbols=len(symbols),
            universe=len(universe),
        )
        return ref

    # ------------------------------------------------------------- accessors

    def ref(self, symbol: str) -> SymbolRef:
        """Reference data for ``symbol``, synthesising defaults when it is unmapped."""
        found = self.symbols.get(symbol)
        return found if found is not None else SymbolRef(symbol=symbol)

    def is_mapped(self, symbol: str) -> bool:
        return symbol in self.symbols

    def sectors(self, symbols: Sequence[str]) -> list[str]:
        return [self.ref(s).sector for s in symbols]

    def betas(self, symbols: Sequence[str]) -> FloatArray:
        return np.array([self.ref(s).beta for s in symbols], dtype=np.float64)

    def prior_volatilities(self, symbols: Sequence[str]) -> FloatArray:
        """Annualised volatilities from config, floored so no symbol is risk-free."""
        vols = np.array([self.ref(s).annualized_volatility for s in symbols], dtype=np.float64)
        return np.maximum(vols, 1e-4)

    def market_weights(self, symbols: Sequence[str]) -> FloatArray:
        """Market-cap weights, normalised to sum to one. Equal weight if caps are unusable."""
        caps = np.array([max(self.ref(s).market_cap, 0.0) for s in symbols], dtype=np.float64)
        total = caps.sum()
        if total <= 0 or not np.isfinite(total):
            return np.full(len(symbols), 1.0 / max(len(symbols), 1), dtype=np.float64)
        return np.asarray(caps / total, dtype=np.float64)

    # -------------------------------------------------------- the prior model

    def prior_correlation(self, symbols: Sequence[str]) -> FloatArray:
        """Block + beta-implied correlation matrix for ``symbols``.

        See the module docstring for the formula. The diagonal is forced to exactly 1 and the
        result is symmetrised, so callers get a well-formed correlation matrix even if the
        configured constants are odd.
        """
        n = len(symbols)
        if n == 0:
            return np.zeros((0, 0), dtype=np.float64)

        p = self.correlation_prior
        sectors = self.sectors(symbols)
        betas = self.betas(symbols)
        vols = self.prior_volatilities(symbols)
        sigma_m_sq = p.market_volatility**2

        same_sector = np.equal.outer(np.array(sectors), np.array(sectors))
        block = np.where(same_sector, p.intra_sector, p.inter_sector).astype(np.float64)

        beta_implied = np.outer(betas, betas) * sigma_m_sq / np.outer(vols, vols)
        beta_implied = np.clip(beta_implied, -1.0, 1.0)

        blend = float(np.clip(p.market_beta_blend, 0.0, 1.0))
        corr = (1.0 - blend) * block + blend * beta_implied
        corr = np.clip(corr, -0.999, 0.999)
        corr = 0.5 * (corr + corr.T)
        np.fill_diagonal(corr, 1.0)
        return corr

    def prior_covariance(self, symbols: Sequence[str]) -> FloatArray:
        """Annualised prior covariance ``D C D`` from the config vols and the block correlation."""
        vols = self.prior_volatilities(symbols)
        corr = self.prior_correlation(symbols)
        return np.outer(vols, vols) * corr
