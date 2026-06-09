# Intraday VaR Risk Check

> **Roadmap:** [3.5.1 — Intraday VaR risk check](https://github.com/drag0sd0g/MariaAlpha/issues/101).
> **TDD reference:** §5.3.3 (Composable Risk Check Chain), FR-24.

## 1. What this is

A pre-trade Value-at-Risk gate. Each order arrives at the risk-check chain after the cheap order-level checks (notional, position, exposure, sector, beta, ADV). The VaR check projects what the portfolio's one-day VaR would look like *if the order filled* and rejects it when the projection exceeds the configured ceiling.

The formula is parametric Gaussian VaR — the same shape used by every desk's morning risk report:

```
per_position_var_i  = |position_notional_i| × σ_ann_i / √trading_days × z(confidence)
portfolio_var       = Σ per_position_var_i           (sum of absolutes — no diversification credit)
```

The portfolio aggregate uses **the sum of absolute per-position VaRs**, not a covariance-matrix integration. That's the conservative reading — it assumes perfect tail-correlation across symbols, which is the right side to err on for a pre-trade gate. A correlation-aware VaR is a future concern (see §6).

## 2. Behaviour

| Situation | Outcome |
|---|---|
| `maxIntradayVar ≤ 0` | Check self-disables (returns PASS). Useful during the rollout before limits are tuned. |
| Symbol's market data missing | FAIL — same convention as every other pre-trade check. |
| Symbol's volatility missing from reference data | PASS — the symbol contributes 0 risk; the check is a safety net, not a substitute for proper data. |
| SELL that reduces an existing long | PASS — the projection grows toward zero, never above the current VaR. |
| BUY that pushes the projection past the limit AND above current VaR | FAIL with `IntradayVar` in `reason`. |
| BUY that pushes past the limit but **below** current VaR (re-balancing) | PASS — a rebalance that reduces total risk is never gated. |

## 3. Configuration

```yaml
execution-engine:
  risk:
    # Cap on projected one-day portfolio VaR in $. Set to 0 to disable.
    max-intraday-var: 750000
    # One-tail confidence level for the z-score. 0.95 → z=1.645; 0.99 → z=2.326.
    var-confidence-level: 0.95
    # Annualised vol → daily vol denominator. 252 (trading days) is the default;
    # 365 (calendar days) is used by some risk frameworks.
    var-trading-days-per-year: 252
    reference-data:
      symbols:
        - symbol: AAPL
          # ...sector/beta/adv...
          annualized-volatility: 0.28    # decimal — 0.28 == 28%/yr
```

The z-score is computed analytically with the inlined Abramowitz & Stegun 26.2.23 rational approximation (~4.5×10⁻⁴ accuracy — plenty for VaR thresholds; tested against the standard 90/95/99% reference values).

## 4. Worked example

Configuration: `max-intraday-var: 750_000`, `var-confidence-level: 0.95`. Existing portfolio:

| Symbol | Position $ | σ_ann | per-position VaR |
| --- | ---: | ---: | ---: |
| NVDA | 10,000,000 | 0.48 | 497,000 |
| TSLA | 5,000,000 | 0.55 | 285,000 |
| **Total** | | | **782,000** |

Already $32K over the cap. Now a BUY arrives for 100 AAPL at $200 (σ_ann=0.28) → projection adds ~$580 to the portfolio VaR → grows above current → **REJECTED**.

A SELL of 5,000 NVDA at the same conditions reduces the NVDA position to $9.5M → portfolio VaR drops below current → **PASSED**, even though the projection still exceeds the cap.

## 5. Test coverage

| Test | What it asserts |
| --- | --- |
| `IntradayVarCheckTest#zScoreMatchesStandardConfidenceLevels` | z(0.90)/z(0.95)/z(0.99) match published reference values. |
| `IntradayVarCheckTest#passesWhenProjectedVarBelowLimit` | Small order on a thinly-positioned book → PASS. |
| `IntradayVarCheckTest#failsWhenProjectedVarBreachesLimit` | Large BUY pushes projection above cap → FAIL. |
| `IntradayVarCheckTest#sellsThatReduceVarPass` | Over-cap portfolio still accepts a flattening SELL. |
| `IntradayVarCheckTest#disabledWhenLimitIsZero` | `max-intraday-var: 0` → unconditional PASS. |
| `IntradayVarCheckTest#unknownSymbolVolatilityContributesZero` | Missing reference data → 0 VaR contribution, PASS. |
| `IntradayVarCheckTest#failsWhenMarketDataMissing` | Missing market state → FAIL with reason. |
| `IntradayVarCheckTest#portfolioVarAccumulatesAcrossSymbols` | Two existing positions add up; a tiny new order pushes past the cap. |

## 6. Limitations and roadmap notes

- **No diversification credit.** Per the §1 formula, portfolio VaR is sum-of-absolutes. A real Phase 3+ extension would integrate a covariance matrix and compute `√(w' Σ w)` for the historical case. The cluster-based [`CorrelatedPositionsCheck`](correlated-positions.md) gives a parallel concentration constraint without paying the full covariance cost.
- **No multi-day horizon.** The check models a one-day window; longer horizons (the SEC's 10-day stress test, e.g.) would scale the z-score by √horizon.
- **No instrument-class differentiation.** Equity vol is the only input; options Greeks are not folded in here. Options pricing lives in `strategy-engine` (3.2.1/3.2.2) and would need its own pre-trade gate once option strategies start submitting orders.
