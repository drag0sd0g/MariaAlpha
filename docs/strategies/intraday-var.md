# Intraday VaR Risk Check

> **Roadmap:** [3.5.1 — Intraday VaR risk check](https://github.com/drag0sd0g/MariaAlpha/issues/101),
> upgraded to covariance aggregation by [4.6.1](https://github.com/drag0sd0g/MariaAlpha/issues/111).
> **TDD reference:** §5.3.3 (Composable Risk Check Chain), FR-24.
> **See also:** [portfolio-risk-engine.md](portfolio-risk-engine.md) — where the covariance model comes from.

## 1. What this is

A pre-trade Value-at-Risk gate. Each order arrives at the risk-check chain after the cheap
order-level checks (notional, position, exposure, sector, beta, ADV). The VaR check projects what
the portfolio's one-day VaR would look like *if the order filled* and rejects it when the
projection exceeds the configured ceiling.

Per-position VaR is the standard parametric Gaussian figure:

```
per_position_var_i = |v_i| × σ_ann,i / √trading_days × z(confidence)
```

**How those combine into a portfolio number is the whole question**, and the check supports two
answers:

```
COVARIANCE        VaR = z √(vᵀ Σ_d v)          signed v, estimated correlations   [default]
SUM_OF_ABSOLUTES  VaR = z Σ_i |v_i| σ_i        the pre-4.6.1 behaviour (ρ = 1)
```

`SUM_OF_ABSOLUTES` is the ρ = 1 corner of the covariance formula — a valid upper bound, and the
conservative side to err on for a pre-trade gate. But it credits no diversification at all, and
because it takes absolute values it cannot distinguish a hedge from a doubled-up position: a book
long \$5M NVDA and short \$5M of correlated names scores identically to one long \$10M NVDA.

For `v = (+\$1M, −\$1M)` at 2% daily volatility and ρ = 0.95 it reports **\$65,794** where the
covariance form reports **\$10,403** — a 6.3× overstatement on a book that is very nearly flat.

The covariance matrix arrives from analytics-service on the compacted `analytics.risk-model`
Kafka topic; see [portfolio-risk-engine.md §5](portfolio-risk-engine.md) for the contract.

## 2. Behaviour

| Situation | Outcome |
|---|---|
| `maxIntradayVar ≤ 0` | Check self-disables (returns PASS). Useful during rollout before limits are tuned. |
| Symbol's market data missing | FAIL — same convention as every other pre-trade check. |
| Symbol's volatility missing from both model and reference data | PASS — the symbol contributes 0 risk; the check is a safety net, not a substitute for proper data. |
| SELL that reduces an existing long | PASS — the projection grows toward zero, never above the current VaR. |
| BUY that pushes the projection past the limit AND above current VaR | FAIL with `IntradayVar` in `reason`, naming the aggregation used. |
| BUY that pushes past the limit but **below** current VaR (re-balancing) | PASS — a rebalance that reduces total risk is never gated. |
| **No risk model has ever arrived** | Falls back to `SUM_OF_ABSOLUTES`; WARN (rate-limited to 1/min); `risk_model_stale = 1`. |
| **Model older than `risk-model-max-age-seconds`** | Same fallback. |
| **Symbol held but absent from the model** | Uses its configured volatility and aggregates at ρ = 1 against everything else. |

### 2.1 Why the fallback matters

Covariance VaR is always ≤ sum-of-absolutes. A stale or absent model would therefore silently
**loosen** the gate — exactly the wrong failure mode. The staleness guard means a dead
analytics-service can only ever make this check more conservative, never less.

## 3. Configuration

```yaml
execution-engine:
  kafka:
    # Compacted topic carrying the covariance/correlation model.
    risk-model-topic: analytics.risk-model
  risk:
    # Cap on projected one-day portfolio VaR in $. Set to 0 to disable.
    max-intraday-var: 750000
    # One-tail confidence level for the z-score. 0.95 → z=1.645; 0.99 → z=2.326.
    var-confidence-level: 0.95
    # Annualised vol → daily vol denominator. 252 (trading days) is the default.
    var-trading-days-per-year: 252
    # COVARIANCE (default) or SUM_OF_ABSOLUTES for the pre-4.6.1 behaviour.
    var-aggregation: ${EXECUTION_ENGINE_VAR_AGGREGATION:COVARIANCE}
    # Beyond this age the cached model is not trusted and the check falls back.
    risk-model-max-age-seconds: ${EXECUTION_ENGINE_RISK_MODEL_MAX_AGE_SECONDS:900}
    reference-data:
      symbols:
        - symbol: AAPL
          # ...sector/beta/adv...
          annualized-volatility: 0.28    # decimal — 0.28 == 28%/yr
```

The reference-data volatilities are the fallback when a symbol is not in the published model.
They mirror `config/portfolio.yml` on the analytics side, and a test pins them so the two cannot
drift apart unnoticed.

The z-score is computed analytically with the inlined Abramowitz & Stegun 26.2.23 rational
approximation (~4.5×10⁻⁴ accuracy — plenty for VaR thresholds; tested against the standard
90/95/99% reference values).

## 4. Worked example

Configuration: `max-intraday-var: 50_000`, `var-confidence-level: 0.95`. Existing portfolio, with
a published model giving ρ(NVDA, MSFT) = 0.95:

| Symbol | Position \$ | σ_ann | σ_daily | standalone VaR |
| --- | ---: | ---: | ---: | ---: |
| NVDA | +10,000,000 × 0.1 | 0.48 | 0.03024 | 49,732 |
| MSFT | −10,000,000 × 0.1 | 0.24 | 0.01512 | 24,866 |

A BUY of 200 NVDA at \$500 (+\$100k) arrives:

| Aggregation | Current VaR | Projected VaR | Verdict at a \$50k limit |
|---|---:|---:|---|
| `SUM_OF_ABSOLUTES` | \$74,604 | \$79,577 | **REJECTED** — over the limit and rising |
| `COVARIANCE` | \$27,241 | \$32,040 | **ACCEPTED** — the hedge is credited |

Same book, same order, same limit. The difference is entirely whether the model is allowed to
know that NVDA and MSFT move together and that one leg is short. This case is pinned by
`IntradayVarCheckTest#hedgedBookPassesUnderCovarianceButFailsUnderSumOfAbsolutes`.

### 4.1 Performance

`Σ_d = D_d C D_d` is materialised **once per model update** (every ~5 minutes), not per order.
Per order the work is one O(N²) quadratic form for the current VaR, and the projected VaR — which
differs in exactly one coordinate — uses the rank-1 identity

```
σ²_new = σ²_old + 2δ (Σ_d v)_k + δ² (Σ_d)_kk
```

which is O(N). This is strictly less work than the pre-4.6.1 implementation, which built two
fresh `BigDecimal` streams per check.

## 5. Test coverage

| Test | What it asserts |
| --- | --- |
| `IntradayVarCheckTest#zscoreMatchesStandardConfidenceLevels` | z(0.90)/z(0.95)/z(0.99) match published reference values. |
| `#passesWhenProjectedVarBelowLimit` | Small order on a thinly-positioned book → PASS. |
| `#failsWhenProjectedVarBreachesLimit` | Large BUY pushes projection above cap → FAIL. |
| `#sellsThatReduceVarPass` | Over-cap portfolio still accepts a flattening SELL. |
| `#disabledWhenLimitIsZero` | `max-intraday-var: 0` → unconditional PASS. |
| `#unknownSymbolVolatilityContributesZero` | Missing reference data → 0 VaR contribution, PASS. |
| `#failsWhenMarketDataMissing` | Missing market state → FAIL with reason. |
| `#portfolioVarAccumulatesAcrossSymbols` | Two existing positions add up; a tiny new order pushes past the cap. |
| `#covarianceAggregationIsLowerThanSumOfAbsolutesWhenCorrelationIsBelowOne` | ρ = 0.30 gives a strictly smaller portfolio VaR. |
| `#hedgedBookPassesUnderCovarianceButFailsUnderSumOfAbsolutes` | **The motivating case** — §4's table. |
| `#sameSignedBookAtPerfectCorrelationMatchesSumOfAbsolutes` | At ρ = 1 the two agree exactly: the new model is a strict generalisation. |
| `#diversificationRatioGaugeExceedsOneWhenCorrelationIsBelowOne` | Metrics are populated. |
| `#fallsBackToSumOfAbsolutesWhenNoModelHasArrived` | An absent model cannot loosen the gate. |
| `#fallsBackToSumOfAbsolutesWhenModelIsStale` | A 20-minute-old model is not trusted. |
| `#unmodelledSymbolUsesReferenceVolatilityAtPerfectCorrelation` | An unmodelled name contributes its full standalone VaR. |
| `#sellReducingRiskStillPassesUnderCovariance` | The reduce-risk rule survives the upgrade. |
| `PortfolioRiskModelTest`, `RiskModelSnapshotTest`, `RiskModelConsumerTest` | Model construction, rank-1 update, validation, malformed-payload handling. |

The first eight run against `SUM_OF_ABSOLUTES`, pinning the pre-4.6.1 semantics unchanged.

## 6. Limitations and roadmap notes

- **The correlation model is only as good as its data.** On the default simulated tape there is
  not enough live history to estimate correlations, so the model falls back to the configured
  block prior in `config/portfolio.yml`. `diagnostics.source` on
  `GET /api/analytics/portfolio/covariance` always says which it is.
- **No analytics-service Helm subchart**, so on the Kubernetes deployment no model is ever
  published and the check permanently uses the sum-of-absolutes fallback. Safe, but the covariance
  path is compose-only until a subchart is added.
- **No multi-day horizon.** The check models a one-day window; longer horizons would scale the
  z-score by √horizon. The analytics-side risk engine already supports an arbitrary
  `horizon_days`.
- **No instrument-class differentiation.** Equity volatility is the only input; options Greeks are
  not folded in. Options pricing lives in `strategy-engine` (3.2.1/3.2.2) and would need its own
  pre-trade gate once option strategies start submitting orders.
- The cluster-based [`CorrelatedPositionsCheck`](correlated-positions.md) remains a parallel
  concentration constraint. It is complementary: it caps gross exposure within a named cluster
  regardless of what the estimated correlation happens to be today.
