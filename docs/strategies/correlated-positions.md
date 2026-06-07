# Correlated Positions Risk Check

> **Roadmap:** [3.5.2 — Correlated position limits](https://github.com/drag0sd0g/MariaAlpha/issues/102).
> **TDD reference:** §5.3.3 (Composable Risk Check Chain), FR-24.

## 1. What this is

A pre-trade concentration check that constrains the gross dollar exposure across a *cluster* of correlated symbols.

The sector check (`SectorExposureCheck`) and beta check (`BetaExposureCheck`) handle the obvious cases: same-industry concentration, leveraged-market-beta drift. But there's a gap they don't cover — symbols across multiple sectors whose returns historically move together because they trade on the same narrative. The classic example today is the AI trade: NVDA (TECH, semis), MSFT (TECH, infra), GOOGL (TECH, ads) might all sit within their sector and beta caps individually, yet a single bad earnings print on any one of them takes all three down at once.

`CorrelatedPositionsCheck` lets the desk express that risk directly: declare a named cluster, list the symbols, set a gross-$ ceiling. Every order that touches one of the cluster's symbols is checked against the ceiling.

## 2. Behaviour

| Situation | Outcome |
|---|---|
| Configured cluster list is empty | PASS (check self-disables) |
| Order symbol is in zero clusters | PASS (the check only constrains symbols that appear in at least one cluster) |
| Order symbol is in N clusters | Each cluster is evaluated independently; the first cluster whose projection exceeds its ceiling AND grows vs. current produces a FAIL |
| Symbol's market data missing | FAIL with reason |
| SELL that reduces a long inside the cluster | PASS — the cluster gross moves toward zero, never above current |
| BUY that pushes the cluster gross past the cap AND grows vs. current | FAIL with the cluster name in the reason |
| BUY/SELL that grows the cluster gross past the cap but **the cluster's current gross is even higher** | PASS — never reject a re-balancing trade |

A symbol can appear in multiple clusters; each cluster is independent. The same MSFT order is checked once for `MEGACAP_TECH` and once for `AI_TRADE` if MSFT is in both.

## 3. Configuration

```yaml
execution-engine:
  risk:
    correlated-clusters:
      - name: MEGACAP_TECH
        symbols: [AAPL, MSFT, GOOGL]
        limit: 1750000
      - name: AI_TRADE
        symbols: [NVDA, MSFT, GOOGL]
        limit: 1500000
      - name: GROWTH_DUO
        symbols: [TSLA, NVDA]
        limit: 1200000
```

No vendor data feed is required — clusters are operator-defined. Production deployments would back them with the desk's risk policy + a periodic refresh from a correlation analysis (e.g. a daily PCA on rolling 90-day return series), but for the MVP the static YAML keeps the operational surface minimal.

## 4. Why static clusters instead of a correlation matrix?

A "real" correlation-aware risk check integrates a covariance matrix and computes `√(w' Σ w)` for the projected portfolio (the standard parametric VaR). That's mathematically cleaner but operationally heavier:

| Concern | Static clusters | Covariance matrix |
| --- | --- | --- |
| Reference data | Operator-defined symbol lists | Daily-refreshed N×N matrix per region |
| Transparency | Trader sees "rejected by MEGACAP_TECH" — actionable | Trader sees "VaR over $750K" — needs decomposition to act |
| Tail correlation | Conservative (= 1 within cluster, 0 outside) | Reflects historical ρ |
| Failure mode | Cluster list goes stale | Matrix drifts every day |
| Implementation cost | One YAML block | Vendor feed + matrix loader + numerical-stability tests |

For an MVP gate, static clusters give 80% of the risk coverage at 10% of the operational cost. The parametric-VaR variant lives next door in [`IntradayVarCheck`](intraday-var.md) and uses sum-of-absolutes precisely because the covariance integration was deferred.

## 5. Test coverage

| Test | What it asserts |
| --- | --- |
| `CorrelatedPositionsCheckTest#passesWhenProjectedClusterGrossBelowLimit` | Small order, cluster under cap → PASS. |
| `CorrelatedPositionsCheckTest#failsWhenProjectedClusterGrossBreachesLimit` | Large BUY pushes cluster gross past cap → FAIL. |
| `CorrelatedPositionsCheckTest#sellThatReducesClusterGrossPasses` | Over-cap cluster still accepts a flattening SELL. |
| `CorrelatedPositionsCheckTest#crossClusterMembershipIsCheckedIndependently` | MSFT in two clusters; first failure short-circuits. |
| `CorrelatedPositionsCheckTest#orderOnSymbolOutsideAnyClusterIsUnconstrained` | AMZN order passes regardless of unrelated cluster state. |
| `CorrelatedPositionsCheckTest#disabledWhenClusterListIsEmpty` | Empty config → unconditional PASS. |
| `CorrelatedPositionsCheckTest#failsWhenMarketDataMissing` | Missing market state → FAIL with reason. |
| `CorrelatedPositionsCheckTest#freshSymbolGetsAddedToProjection` | Order on a symbol with no existing position correctly grows the cluster. |
