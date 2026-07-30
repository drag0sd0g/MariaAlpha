# Portfolio Risk Engine

> **Roadmap:** [4.6.1 — Implement portfolio optimization (mean-variance)](https://github.com/drag0sd0g/MariaAlpha/issues/111).
> **TDD reference:** §5.2.7 (Analytics Service), §5.4 (Kafka topics), FR-48.
> **Companion docs:** [portfolio-construction.md](portfolio-construction.md) · [intraday-var.md](intraday-var.md)

## 1. The defect this fixes

Before 4.6.1, `IntradayVarCheck` aggregated per-position VaR as a **sum of absolute values**:

```java
return positions.entrySet().stream()
    .map(e -> positionVar(e.getKey(), e.getValue(), zscore, sqrtT))   // uses .abs()
    .reduce(BigDecimal.ZERO, BigDecimal::add);
```

Two consequences:

1. **No diversification credit.** `VaR_p = Σ VaR_i` is the ρ = 1 corner of the covariance
   formula. It is a correct *upper bound*, and choosing it was a defensible conservative
   decision — but it means a book long \$5M NVDA and short \$5M of correlated names is scored
   identically to a book long \$10M NVDA. The desk is punished for hedging.
2. **The sign is discarded.** Because `positionVar` takes `.abs()`, a short adds risk exactly
   like a long. A market-neutral pair trade can never reduce measured risk.

For `v = (+\$1M, −\$1M)` at 2% daily volatility and ρ = 0.95:

```
sum-of-absolutes:  z (|v₁|σ₁ + |v₂|σ₂)                              = $65,794
covariance:        z √(v₁²σ₁² + v₂²σ₂² + 2 v₁v₂ σ₁σ₂ ρ)             = $10,403
```

A **6.3× overstatement** on a book that is very nearly flat. That gap is the deliverable.

---

## 2. The three VaR methods

Reported side by side rather than picking one, because their disagreement is the information.

### 2.1 Parametric (variance–covariance)

```
σ_p   = √(vᵀ Σ_d v)          v = signed notional vector, in $
VaR   = z_α · σ_p · √h
ES    = φ(z_α)/(1−α) · σ_p · √h
```

The Gaussian ES/VaR ratio is a fixed **1.2535** at 95%. Fast and differentiable; wrong in the
tail, because equity returns are leptokurtic. That is *why* two more methods ship.

### 2.2 Historical simulation

Revalue today's book under every historical return vector:

```
PnL_t = vᵀ r_t
VaR   = −quantile(PnL, 1−α)
ES    = −mean( PnL | PnL ≤ −VaR )
```

No distributional assumption, so it keeps whatever fat tails and correlation breakdown are in the
sample. Its weakness is that it cannot produce a loss bigger than the worst thing in the window,
and the window here is short.

**Two honesty guards.** Bars are aggregated into non-overlapping horizon blocks when there are
enough of them — the assumption-free version. When there are not (the usual case on an intraday
window), the per-bar P&L distribution is scaled by √t and a **note says so**, because that step
quietly reintroduces the i.i.d. assumption historical simulation exists to avoid. And
`sufficient: false` is set below `2/(1−α)` observations — 40 at 95% — rather than returning a
confident number built on nine data points.

### 2.3 Monte Carlo

```
Σ_d = L Lᵀ                              (Cholesky, after PSD repair)
z   ~ N(0, I)  or  t_ν scaled to unit variance
r   = L z √h
PnL = vᵀ r
```

`ANALYTICS_RISK_MC_SIMULATIONS` paths (default 10,000), seeded from
`ANALYTICS_RISK_MC_SEED` so CI is deterministic. Set `"distribution": "t"` for Student-*t*
innovations with `ν` d.o.f. (default 5), **rescaled by `√((ν−2)/ν)`** so the marginal variance
still matches `Σ` — without that rescale the *t* draws would inflate volatility as well as the
tail, double-counting the effect. Typically lifts 99% VaR by 20–35% over the Gaussian.

Monte Carlo is also the only one of the three that extends to non-linear instruments, which is
why the plumbing is worth having before [options](options-pricing.md) become book positions.

---

## 3. Component VaR — who owns the risk

`σ_p` is homogeneous of degree one in `v`, so Euler's theorem gives an **exact** additive
decomposition:

```
MVaR_i = z_α (Σ_d v)_i / σ_p · √h        marginal — $ of VaR per $ of extra exposure to i
CVaR_i = v_i · MVaR_i                     component
Σ_i CVaR_i = VaR                          exactly
```

Component VaR goes **negative** for a genuine hedge. That is precisely the information the
sum-of-absolutes model destroys, and it is why this table is the headline of the risk page.

Worked example from the live stack — long \$1M NVDA against a short \$1M MSFT:

| Symbol | Notional | Standalone VaR | Component VaR |
|---|---:|---:|---:|
| NVDA | +\$1,000,000 | \$49,732 | **+\$43,096** |
| MSFT | −\$1,000,000 | \$24,866 | **−\$1,349** |
| | | Σ = \$74,599 | Σ = \$41,748 = portfolio VaR |

The MSFT short is *reducing* total risk. The old model scored it as adding \$24,866.

### 3.1 Diversification ratio

```
DR = (Σ_i z σ_i |v_i|) / VaR_p  =  what the old model reported / what the new one reports
```

`DR = 1` means no diversification; `DR = 6.32` is §1's worked case. Every response carries both
numbers plus the ratio, so the value of the upgrade stays **continuously auditable** rather than
being a claim in a pull-request description. It is also a Prometheus gauge on both services.

---

## 4. Stress scenarios

VaR answers "how bad is a bad day, statistically". Stress answers "how bad is *this* day", and
the two disagree exactly when it matters. Declared in `config/stress-scenarios.yml`:

**FACTOR** — a market move propagated through each name's beta:

```
r_i = β_i · market_shock + idiosyncratic_i
```

with volatilities scaled by `vol-multiplier` and correlations pulled toward
`correlation-override`. **The correlation override is the important half**: in a crisis
cross-sectional correlations converge toward one and the diversification the covariance model
credited you with evaporates. A stress test that keeps the calm-market correlation matrix is
theatre — it will always report that the hedged book is fine, which is the one thing a stress
test is supposed to be able to contradict.

The idiosyncratic term is taken at its stressed one-sigma level and signed **against** the
position, so the scenario is a genuine adverse case rather than a coin flip.

**EXPLICIT** — per-symbol or per-sector percentage moves with a `default-shock` for the rest.
This is how you replay a specific date or express a thematic unwind.

Shipped scenarios: `MARKET_DOWN_10`, `MARKET_UP_10` (surfaces short-book exposure),
`TECH_SELLOFF`, `COVID_20200316` (S&P −11.98%, VIX 82), `VOLMAGEDDON_20180205` (S&P −4.10%),
`AI_UNWIND`.

Breaches publish a `STRESS_LIMIT_BREACH` event on `analytics.risk-alerts` — the same topic the
api-gateway already fans out over `/ws/alerts`, which the UI `AlertsBanner` already renders. Zero
extra plumbing for a visible result. VaR-limit breaches publish `PORTFOLIO_VAR_BREACH` the same
way.

---

## 5. The Kafka seam: `analytics.risk-model`

The estimator lives in Python because that is where numpy is; the pre-trade gate lives in
execution-engine because it sits on the order hot path (NFR §4.1) and a synchronous HTTP hop per
order is not acceptable. This topic is the seam.

### 5.1 Topic

Key `"GLOBAL"`, **compacted** rather than time-retained: only the latest model matters, and a
restarting execution-engine reading from `earliest` gets it immediately instead of running on the
conservative fallback until the next publish interval elapses.

```bash
create_compacted_topic "analytics.risk-model" 1   # config/kafka/create-topics.sh
```

### 5.2 Payload

```json
{
  "modelId": "cov-20260729T140211Z",
  "generatedAt": "2026-07-29T14:02:11Z",
  "estimator": "ewma+ledoit_wolf",
  "source": "sample+prior",
  "barSeconds": 60,
  "observations": 128,
  "shrinkageIntensity": 0.34,
  "tradingDaysPerYear": 252.0,
  "symbols": ["AAPL", "MSFT", "GOOGL", "AMZN", "TSLA", "NVDA"],
  "annualizedVolatility": [0.281, 0.241, 0.269, 0.318, 0.552, 0.477],
  "correlation": [[1.0, 0.61, ...], ...],
  "psdRepaired": false,
  "conditionNumber": 18.7
}
```

**Volatilities plus a correlation matrix, not a raw covariance.** Three reasons: the payload is
readable in `kafka-console-consumer`; the two halves carry independent trust, so the Java side can
substitute a configured volatility for a thinly-traded name while keeping the estimated
correlation structure; and a unit-diagonal correlation matrix is trivially validatable, which
makes a malformed model easy to reject rather than easy to miss.

### 5.3 Validation

`RiskModelSnapshot.validate()` rejects the whole payload on: empty symbols, missing
`generatedAt`, length mismatch, non-square or ragged correlation, `|ρ| > 1`, non-unit diagonal,
asymmetry beyond 1e−6, non-finite or negative volatility, non-positive trading days. A rejected
payload leaves the previous model in place; staleness handles the rest.

---

## 6. The pre-trade gate

### 6.1 Latency

The daily covariance `Σ_d = D_d C D_d` is materialised **once per model update** (every ~5
minutes), not per order, and read lock-free through a `volatile` holder. Per order:

- `currentVar` is one quadratic form `vᵀΣ_d v` — O(N²), ≤ 2,500 multiply-adds at N = 50
- `projectedVar` differs in exactly one coordinate, so it uses the **rank-1 update**

```
σ²_new = σ²_old + 2δ (Σ_d v)_k + δ² (Σ_d)_kk
```

which is O(N) given a cached `Σ_d v`. `PortfolioRiskModelTest#sigmaAfterDeltaEqualsFullRecompute`
asserts it against a full recompute over 200 random books.

### 6.2 Safety: a dead brain must not loosen the gate

Covariance VaR is always ≤ sum-of-absolutes, so a stale or absent model would silently *loosen*
the pre-trade limit. Three guards:

| Situation | Behaviour |
|---|---|
| No model has ever arrived | Fall back to `SUM_OF_ABSOLUTES`, WARN (rate-limited to 1/min), `risk_model_stale = 1` |
| Model older than `risk-model-max-age-seconds` (default 900) | Same |
| Symbol held but absent from the model | Uses its configured volatility, aggregated at ρ = 1 against everything else — conservative when uninformed |

The "projected exceeds the limit **and** exceeds current" rule is unchanged, so a trade that
reduces total risk is still never blocked.

### 6.3 Configuration

```yaml
execution-engine:
  kafka:
    risk-model-topic: analytics.risk-model
  risk:
    var-aggregation: ${EXECUTION_ENGINE_VAR_AGGREGATION:COVARIANCE}
    risk-model-max-age-seconds: ${EXECUTION_ENGINE_RISK_MODEL_MAX_AGE_SECONDS:900}
```

Set `SUM_OF_ABSOLUTES` to pin the pre-4.6.1 behaviour. Both are also plumbed through
`docker-compose.yml` (along with `EXECUTION_ENGINE_RISK_MAX_INTRADAY_VAR`) so the two aggregations
can be A/B-compared on a running stack without rebuilding the image.

> **Config constraint: `risk-model-max-age-seconds` must comfortably exceed
> `ANALYTICS_RISK_MODEL_PUBLISH_SECONDS`.** Otherwise the model spends most of its life past the
> ceiling and the gate flaps between aggregations between publishes — every order near the limit
> then gets a different answer depending on when it arrives. The shipped defaults are 900 s against
> a 300 s publish interval, which is 3x headroom. If you shorten the publish interval (the demo
> overlay uses 30 s), the ceiling can come down with it; if you lengthen it, raise the ceiling
> first.

---

## 7. Metrics

**analytics-service** (scraped by Alloy at `analytics-service:8095`):

| Metric | Type |
|---|---|
| `mariaalpha_analytics_portfolio_var_usd{method,confidence}` | Gauge |
| `mariaalpha_analytics_portfolio_expected_shortfall_usd{method,confidence}` | Gauge |
| `mariaalpha_analytics_diversification_ratio` | Gauge |
| `mariaalpha_analytics_covariance_observations` | Gauge |
| `mariaalpha_analytics_covariance_shrinkage_intensity` | Gauge |
| `mariaalpha_analytics_optimizer_runs_total{objective,converged}` | Counter |
| `mariaalpha_analytics_optimizer_seconds{objective}` | Summary |
| `mariaalpha_analytics_stress_breaches_total{scenario}` | Counter |
| `mariaalpha_analytics_risk_model_published_total` | Counter |

**execution-engine**:

| Metric | Type |
|---|---|
| `mariaalpha_execution_var_usd{kind="current"\|"projected"}` | Gauge |
| `mariaalpha_execution_var_diversification_ratio` | Gauge |
| `mariaalpha_execution_risk_model_age_seconds` | Gauge |
| `mariaalpha_execution_risk_model_stale` | Gauge (0/1) |
| `mariaalpha_execution_risk_model_symbols` | Gauge |

`risk_model_stale` is the one to alert on: it going to 1 means the pre-trade gate has silently
reverted to the conservative aggregation.

---

## 8. REST surface

| Method | Path (`/api/analytics/…`) | Purpose |
|---|---|---|
| GET | `risk/var` | All three VaR methods + ES for the live book |
| POST | `risk/var` | Same for a supplied book / confidence / horizon / distribution |
| GET | `risk/components` | Marginal + component VaR table (Euler) |
| GET/POST | `risk/stress` | Scenario P&L table, optionally filtered by name |
| GET | `risk/report` | Self-contained HTML report (inline SVG heat map, no CDN) |

---

## 9. Test coverage

| Test | What it asserts |
|---|---|
| `test_risk_engine.py::test_hedged_book_gets_large_diversification_credit` | Pins the exact §1 numbers: \$10,403 vs \$65,794, ratio 6.3246 |
| `…::test_perfect_correlation_and_equal_opposite_positions_gives_zero_var` | ρ = 1 hedged ⇒ VaR ≈ 0 |
| `…::test_same_signed_book_at_perfect_correlation_equals_sum_of_absolutes` | The new model is a strict generalisation of the old one |
| `…::test_components_sum_exactly_to_portfolio_var` | Euler identity to 1e−12 |
| `…::test_a_genuine_hedge_has_negative_component_var` | The sign that the old model could not express |
| `…::test_student_t_fattens_the_tail_at_high_confidence` | *t* > normal at 99% |
| `test_stress.py` (28 cases) | Beta propagation, explicit overrides, breach detection, YAML loading |
| `PortfolioRiskModelTest` | `Σ_d` construction, rank-1 update == full recompute over 200 random books, staleness, defensive copying |
| `RiskModelSnapshotTest` | Every validation rejection path |
| `RiskModelConsumerTest` | Malformed JSON swallowed; a rejected payload keeps the previous model |
| `IntradayVarCheckTest` | Hedged book **passes** under covariance and **fails** under sum-of-absolutes at the same \$50k limit; stale/absent model falls back |

---

## 10. Limitations

- **No analytics-service Helm subchart**, so on the Kubernetes deployment the risk model is never
  published and the VaR check permanently uses the conservative fallback. Safe, but the feature is
  compose-only until a subchart is added.
- **Delta-normal only.** Options are not book positions yet; the Monte-Carlo plumbing is there for
  when they are.
- **No multi-currency risk.** [Currency exposure](currency-exposure.md) covers exposure; FX
  covariance is out of scope.
- **Historical VaR window is short** on the local stack; `sufficient: false` marks it honestly.
- **√t horizon scaling** inherits the i.i.d. assumption throughout.
