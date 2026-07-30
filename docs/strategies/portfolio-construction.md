# Portfolio Construction

> **Roadmap:** [4.6.1 — Implement portfolio optimization (mean-variance)](https://github.com/drag0sd0g/MariaAlpha/issues/111).
> **TDD reference:** §5.2.7 (Analytics Service), FR-47.
> **Companion doc:** [portfolio-risk-engine.md](portfolio-risk-engine.md) — the VaR/ES/stress half of 4.6.1.

## 1. What this is

Everything else in MariaAlpha answers *"how do I get to a target position"*: strategies emit
signals, the algo API slices a parent order, the SOR picks a venue, the risk chain gates it, the
order manager books it. Nothing answered *"what should the target be"*. This is that layer.

It lives in `analytics-service` (`src/analytics/portfolio/`) because the maths needs numpy and
scipy, and it hands its answer to the already-shipped
[program/basket trading](program-basket-trading.md) service — `POST /api/execution/baskets` — so
the last mile is free.

```
market-data.ticks ─┐
                   ├─► covariance estimate ─► optimiser ─► cost-aware rebalancer ─► basket order
positions.updates ─┘         │
config/portfolio.yml ────────┴─► Black-Litterman equilibrium ─► expected returns
```

---

## 2. Covariance estimation

Everything downstream reduces to one matrix, so this is where the care goes.

### 2.1 Why not the sample covariance

`S` estimates `N(N+1)/2` parameters from `T × N` numbers. When `T` is comparable to `N` its
extreme eigenvalues are badly biased — the largest too large, the smallest too small
(Marchenko–Pastur) — and **every optimiser inverts it**, which amplifies exactly the directions
that are least well estimated. With `T < N` it is singular outright.

### 2.2 EWMA (RiskMetrics)

```
Σ_t = λ Σ_{t−1} + (1 − λ) r_t r_tᵀ
```

Equivalently a weighted sample covariance with weights `α λ^(T−1−t)`, `α = (1−λ)/(1−λ^T)`.
Default `λ = 0.97` for intraday bars. Captures volatility clustering, which an equal-weighted
window averages away.

### 2.3 Ledoit–Wolf shrinkage

*Honey, I Shrunk the Sample Covariance Matrix* (2004). Blend the noisy estimator toward a
structured target:

```
Σ̂ = δ F + (1 − δ) S
δ* = clip( (π̂ − ρ̂) / γ̂ / T , 0, 1 )
```

- `π̂` — summed asymptotic variance of the sample covariances
- `ρ̂` — summed asymptotic covariance between target and sample
- `γ̂ = ‖F − S‖²_F` — how badly the target is misspecified

`F` is the **constant-correlation target** (sample variances on the diagonal, the average sample
correlation off it), blended 50/50 with the configured prior so the shrinkage destination still
carries sector structure. Implemented from the paper's Appendix B rather than borrowing
`sklearn.covariance.LedoitWolf`, which shrinks toward a *scaled identity* — a target that throws
away the correlation structure this feature exists to capture. `δ` is floored at
`ANALYTICS_COVARIANCE_SHRINKAGE_FLOOR` (default 0.20) because `T` here is honestly small.

### 2.4 The correlation prior

Nobody maintains a 50×50 matrix by hand, and the estimator needs a target that is always
available and always positive-definite. `config/portfolio.yml` declares a **block model**:

```
ρ_ij = (1 − b) · block_ij + b · min(1, β_i β_j σ_m² / (σ_i σ_j))
```

`block_ij` is `intra-sector` when i and j share a sector, `inter-sector` otherwise; the second
term is what a single-factor market model implies. Both are valid correlation structures, so the
blend is PSD for `b ∈ [0,1]` and `block ∈ [0,1)`. The prior does double duty: it is the whole
model on a cold start, and the shrinkage target once live returns exist.

### 2.5 PSD repair and annualisation

Eigenvalue clipping with a diagonal rescale so marginal volatilities survive the repair, reported
through `psdRepaired` — silently repairing a matrix is how people end up trusting a number that
has been quietly rewritten.

```
Σ_annual = Σ_bar × (seconds_per_year / bar_seconds)      seconds_per_year = 252 × 6.5 × 3600
σ_daily  = σ_annual / √252
```

Square-root-of-time is wrong (returns are autocorrelated, volatility clusters) and is what every
desk uses. The pre-existing `IntradayVarCheck` already assumed it, so at least the convention is
consistent across the system.

### 2.6 The three-tier data source

| Tier | Source | Always available? | Role |
|---|---|---|---|
| 1 | Config prior from `config/portfolio.yml` | Yes | Cold-start model **and** shrinkage target |
| 2 | Live bars from `MarketDataCache` | After `ANALYTICS_RETURNS_MIN_OBSERVATIONS` (30) | The empirical signal |
| 3 | Caller-supplied `covariance` in the request | On demand | What-if analysis, deterministic tests |

**The arrival clock.** `ANALYTICS_RETURNS_CLOCK` defaults to `arrival`, bucketing ticks by
wall-clock ingest time rather than by the tick's own timestamp. This is not cosmetic: the
simulated market-data tape is 26 rows spanning 4.5 seconds, and `SimulatedMarketDataAdapter`
loops it **without rewriting timestamps**. On the event axis the tape repeats forever and the
return series is degenerate — near-zero volatility, undefined correlations. Set `event` for a
live Alpaca feed, where the two axes agree up to ingest latency.

Every covariance-bearing response carries a `diagnostics` block naming its provenance:

```json
{ "source": "sample+prior", "observations": 128, "barSeconds": 60,
  "shrinkageIntensity": 0.34, "psdRepaired": false, "conditionNumber": 18.7,
  "symbolsFromPrior": ["AMZN"] }
```

---

## 3. The optimisers

Notation: `N` assets, weights `w` (`Σwᵢ = 1`), expected excess returns `μ`, covariance `Σ`.

### 3.1 Mean-variance (Markowitz 1952)

```
maximise   μᵀw − (λ/2) wᵀΣw
subject to 1ᵀw = 1,  l ≤ w ≤ u,  optional sector caps Aw ≤ b
```

Closed forms used as warm starts and as what the tests check against:

| Portfolio | Formula |
|---|---|
| Global minimum variance | `w = Σ⁻¹1 / (1ᵀΣ⁻¹1)` |
| Tangency / max Sharpe | `w = Σ⁻¹μ / (1ᵀΣ⁻¹μ)` |

Constrained solves use `scipy.optimize.minimize(method="SLSQP")` with **analytic gradients**
(`∇U = μ − λΣw`). Finite-difference gradients on a 50-dimensional problem are slow, flaky near
the bounds, and produce "converged" answers that are silently wrong.

`numpy.linalg.inv` is banned in this package: every solve goes through `scipy.linalg.cho_factor`
/ `cho_solve`. On an ill-conditioned covariance that is the difference between a usable answer
and noise.

### 3.2 Max Sharpe under constraints

A ratio, which SLSQP handles badly. Use the Cornuejols–Tütüncü reformulation: minimise `yᵀΣy`
subject to `μᵀy = 1`, `y ≥ 0`, then normalise `w = y / 1ᵀy`. Convex and reliable. When no
feasible portfolio has positive expected return the optimiser falls back to minimum variance and
**says so in `message`** rather than returning a sign-flipped answer that looks like a
recommendation.

### 3.3 Risk parity / equal risk contribution

Portfolio volatility is homogeneous of degree one, so Euler's theorem gives an exact
decomposition:

```
RC_i = w_i (Σw)_i / σ_p        Σ_i RC_i = σ_p    (exactly)
```

Equal risk contribution asks for `RC_i = σ_p / N`. The obvious objective
`min Σ_ij (RC_i − RC_j)²` is **non-convex** with bad local minima. Use **Spinu's (2013)
log-barrier form**, which is strictly convex on the positive orthant:

```
minimise  ½ yᵀΣy − Σ_i b_i ln(y_i),   then  w = y / 1ᵀy
```

Its stationarity condition `y_i (Σy)_i = b_i` *is* proportional risk contribution with budget
`b`. Setting `b = 1/N` gives ERC; a general `b` gives **risk budgeting** (`riskBudget` in the
request, e.g. "40% of risk in tech"). Solved over `ln y` so positivity is structural rather than
a constraint, warm-started from inverse-volatility weights — the exact answer when correlations
are equal.

**When the box binds, ERC is unreachable.** The barrier form has no notion of an upper bound, so
any box is applied afterwards by clip-and-redistribute (a single clip-then-renormalise does not
work — renormalising scales the clipped entries straight back above the cap). When clipping
actually changes the answer, `message` says `box constraints bind, so risk contributions are no
longer exactly equal`, rather than labelling the result "risk parity" when its contributions are
not equal.

### 3.4 The baselines

**Equal weight** (`w = 1/N`) and **inverse volatility** (`w_i ∝ 1/σ_i`) ship as first-class
objectives, not afterthoughts. They are the benchmarks optimisers routinely fail to beat out of
sample, and they are closed-form, so there is always a working answer when a solver misbehaves on
a degenerate matrix.

### 3.5 Diagnostics

Every result carries `converged`, `iterations`, the solver `message`, and:

- **`diversificationRatio`** — weighted average volatility over portfolio volatility
- **`effectiveN` = 1/Σw²** — the inverse Herfindahl. The single most useful sanity number: a
  "diversified" portfolio with `effectiveN = 1.4` is not diversified.

---

## 4. Black–Litterman

### 4.1 The problem it solves

Sample means are hopeless as an input to `Σ⁻¹μ`. The standard error of a mean return estimated
from `T` years is `σ/√T`; at 25%/yr volatility over five years that is **11%** — the same order
of magnitude as the quantity being estimated. Feed that into a mean-variance optimiser and you
get the notorious corner solutions: 90% in whichever asset had the luckiest sample.

BL's answer: don't estimate `μ` at all. Start from the returns the market's own positioning
implies, and move away only as far as your views justify.

### 4.2 The four steps

**Reverse optimisation.** If the market-cap portfolio is what a representative investor with risk
aversion `λ_mkt` would hold, the first-order condition run backwards gives the equilibrium excess
returns:

```
Π = λ_mkt Σ w_mkt
```

**Views.** `K` views as a pick matrix `P (K×N)` and target vector `Q`. A row picking one asset is
*absolute* ("NVDA returns 12%"); a row summing to zero is *relative* ("AAPL beats MSFT by 3%").

**View uncertainty.** He–Litterman's proportional convention, so a view on a volatile combination
is automatically held less tightly:

```
Ω = diag(τ P Σ Pᵀ) / confidence
```

**Posterior** (Theil mixed estimation):

```
M      = [ (τΣ)⁻¹ + Pᵀ Ω⁻¹ P ]⁻¹
E[R]   = M [ (τΣ)⁻¹ Π + Pᵀ Ω⁻¹ Q ]
Σ_post = Σ + M
```

Two implementations ship. The default uses the Sherman–Morrison–Woodbury identity, factoring only
the `K×K` matrix `Ω + τPΣPᵀ` — with `K` typically 1–3 and `N` up to a few hundred that is both
faster and better conditioned. The naive form is kept because it is the literal transcription of
the formulas, and the tests assert the two agree to 1e−10: an optimisation you cannot check
against the definition is a liability.

### 4.3 Properties the tests pin

| Property | Assertion |
|---|---|
| Reverse optimisation is correct | Feeding `Π` back through the unconstrained MV first-order condition recovers `w_mkt` to 1e−12 |
| No views ⇒ no change | `E[R] == Π` exactly |
| Woodbury == naive | Agree to 1e−10 |
| Monotone in confidence | Raising a view's confidence moves the posterior toward `Q` |
| **He–Litterman span theorem** | `w_post − w_mkt` lies exactly in the span of `Pᵀ` (residual 1e−16) |

That last one is worth stating precisely, because it is commonly mis-stated as "a relative view
leaves the cap-weighted mean unchanged" — which is **not true**. What is true is that the optimal
*weight change* is a linear combination of the view portfolios: a relative AAPL-vs-MSFT view tilts
AAPL against MSFT and moves nothing else in the book.

### 4.4 Where `μ` comes from by default

`POST /api/analytics/portfolio/optimize` defaults `expectedReturns` to the BL equilibrium and
reports `expectedReturnSource: "black-litterman-equilibrium"`. There is no alpha model feeding
`μ` in MariaAlpha today; wiring the ML signal service's confidence in as a BL view is the obvious
next step and is left as follow-up work.

---

## 5. Factor exposure decomposition

Two decompositions ship together because each one checks the other.

### 5.1 Fundamental (BARRA-lite)

Exposure matrix `B (N×K)` from reference data:

| Factor | Exposure of asset *i* | Source |
|---|---|---|
| `MARKET` | `β_i` | `config/portfolio.yml` |
| `SIZE` | z-score of `ln(marketCap_i)` | `config/portfolio.yml` |
| `VOLATILITY` | z-score of `σ_i` | estimated covariance diagonal |
| `MOMENTUM` | z-score of trailing return | live tick history (omitted when absent) |
| `SECTOR_<X>` | 1 if asset *i* is in sector X | `config/portfolio.yml` |

Portfolio exposure is `x = Bᵀw`. Risk decomposition:

```
Σ ≈ B Σ_f Bᵀ + Δ                 Δ = diag(specific variances)
σ²_model = wᵀ B Σ_f Bᵀ w  +  wᵀ Δ w
           (systematic)      (idiosyncratic)
```

`Σ_f` comes from cross-sectional WLS: at each `t`, regress the cross-section of returns on `B`
with weights `1/σ_i` (the standard BARRA choice — an equal-weighted cross-sectional regression
lets the noisiest names dominate). Without live returns, `Σ_f` is the projection of the prior
covariance onto the factor space via the pseudo-inverse, so `B Σ_f Bᵀ = P Σ Pᵀ` with
`P = B·pinv(B)` a genuine orthogonal projector.

**Honesty about what adds up.** A factor model is an *approximation* to `Σ`: it matches the
diagonal but not the off-diagonal, so `σ²_model ≠ wᵀΣw` in general. The response reports both,
plus `modelFit = modelVariance / covarianceVariance`. A value far from 1.0 means the named
factors do not span the portfolio's actual risk.

**Saturation.** With six factors over a six-asset universe, `B` is full rank and the projector is
the identity — *everything* classifies as systematic by construction. That is an artefact of the
setup, not a finding, so `notes` says so explicitly. The fundamental model only becomes
informative when `N ≫ K`.

### 5.2 Statistical (PCA)

Eigen-decompose the *correlation* matrix, report variance explained per component, the portfolio's
loading on each, and the top names in PC1. Needs no reference data, always works, and is the
honest check on the fundamental model: if PC1 explains 70% and your named factors account for
30% of variance, your factor model is decorative.

---

## 6. Rebalancing with transaction costs

### 6.1 Why costs go inside the objective

Re-solving the optimiser every period and trading to the answer loses money. The optimiser is
acutely sensitive to estimation noise in `μ`, so the target wanders even when nothing real has
changed, and every wander costs spread plus impact.

```
maximise  μᵀw − (λ/2) wᵀΣw − TC(w, w₀)     subject to  1ᵀw = 1, l ≤ w ≤ u
```

### 6.2 The cost model

Per asset, with `Q_i = |w_i − w₀ᵢ| · NAV`:

```
TC_i = c_i Q_i                                       spread + commission (linear, bps)
     + η σ_daily,i Q_i √( Q_i / (ADV_i · P_i) )       square-root market impact
```

The impact term is the Almgren–Chriss-family `η σ √(Q/V)` shape already used by the
[Implementation Shortfall](implementation-shortfall.md) strategy, so the two places in this
codebase that price impact price it the same way. It is convex on `Q ≥ 0` (`Q^1.5` is), which
keeps the problem well behaved. Doubling a trade multiplies the impact term by exactly `2√2` —
asserted in the tests.

### 6.3 Making it smooth

`|w − w₀|` is non-differentiable at zero, which stalls SLSQP exactly where most assets want to
sit. Use the exact **buy/sell split** `w = w₀ + p − q` with `p, q ≥ 0`. Since costs are strictly
positive, no optimum has `p_i` and `q_i` both positive, so `|Δw_i| = p_i + q_i` holds at the
solution and the objective is smooth everywhere. The problem doubles in dimension, which is free
at this scale.

### 6.4 The no-trade band

The cost penalty alone still emits 0.01%-of-NAV trades no desk would send. Three post-processing
filters are what actually stop the churn:

| Filter | Setting | Default |
|---|---|---|
| Minimum trade notional | `ANALYTICS_REBALANCE_MIN_TRADE_NOTIONAL` | `$2,500` |
| Band in bps of NAV | `ANALYTICS_REBALANCE_NO_TRADE_BAND_BPS` | `25` |
| Lot rounding | `ANALYTICS_REBALANCE_LOT_SIZE` | `1` |

Both the surviving legs and the **suppressed** ones are reported, so the band's effect is visible
instead of silently swallowing trades.

### 6.5 Output

`basketOrderRequest` matches `BasketOrderRequest` / `BasketLegRequest` exactly and can be POSTed
verbatim to `/api/execution/baskets`:

```json
{ "name": "rebalance-20260729T140500Z",
  "legs": [ {"symbol":"AAPL","side":"SELL","orderType":"MARKET","quantity":420,"tif":"DAY"} ] }
```

`BasketLegRequest.quantity` is annotated `@Min(1)` on the Java side, so zero-share legs are
filtered out before they reach the wire.

**Submission is opt-in.** `POST /api/analytics/portfolio/rebalance` only computes. A separate
`POST /api/analytics/portfolio/rebalance/submit` actually sends, returns **503** unless
`ANALYTICS_REBALANCE_SUBMIT_ENABLED=true`, and logs the full payload at INFO first. Sending real
orders from an analytics service is a foot-gun.

---

## 7. Configuration

`config/portfolio.yml`, mounted at `/app/config/portfolio.yml`:

```yaml
portfolio:
  universe: [AAPL, MSFT, GOOGL, AMZN, TSLA, NVDA]
  risk-aversion: 3.0            # lambda in the mean-variance objective
  market-risk-aversion: 2.5     # lambda_mkt for Black-Litterman reverse optimisation
  tau: 0.05
  correlation-prior:
    intra-sector: 0.65
    inter-sector: 0.35
    market-beta-blend: 0.5
    market-volatility: 0.18
  cost:
    commission-bps: 0.5
    default-half-spread-bps: 1.0
    impact-eta: 1.0
  symbols:
    - symbol: NVDA
      sector: TECH
      beta: 1.65
      adv: 250000000
      market-cap: 3000000000000
      annualized-volatility: 0.48
      half-spread-bps: 1.0
```

Sector / beta / ADV / volatility **mirror `execution-engine.risk.reference-data`** in
`execution-engine/src/main/resources/application.yml`; a test
(`test_reference.py::test_shipped_volatilities_match_the_execution_engine_reference_data`) pins
them so the two files cannot drift apart unnoticed. Market cap is analytics-only.

A missing or malformed file does not stop the service booting — it falls back to hard-coded
defaults, logs loudly, and reports `source_path: null`.

---

## 8. Worked example

Six-symbol universe, prior-only covariance, `max_weight = 0.35`, `λ = 3`:

```
$ curl -s "$H" -X POST $GW/api/analytics/portfolio/optimize \
    -d '{"objective":"RISK_PARITY","constraints":{"max_weight":0.35}}' | jq

  weights           AAPL 0.182  MSFT 0.217  GOOGL 0.194  AMZN 0.184  TSLA 0.111  NVDA 0.112
  riskContributions   all six = 0.040606          <- equal by construction
  effectiveN        5.66  (out of 6)
```

Risk parity puts *less* weight on TSLA and NVDA precisely because their volatilities (55% and
48%) are roughly double MSFT's (24%) — equalising risk contribution, not capital. Compare with
`EQUAL_WEIGHT`, where the two high-volatility names would dominate the risk budget.

---

## 9. Test coverage

| Test | What it asserts |
|---|---|
| `test_covariance.py` (44 cases) | Closed forms, shrinkage bounds, PSD repair preserves the diagonal, annualisation, prior fallback |
| `test_optimizers.py` (48) | 2-asset min-variance matches the analytic formula to 1e−12; ERC spread < 1e−6·σ; inverse-vol == risk parity when `C = I`; max-Sharpe matches closed-form tangency; box constraints respected |
| `test_black_litterman.py` (22) | Reverse optimisation recovers `w_mkt`; no views ⇒ posterior == Π; Woodbury == naive; He–Litterman span theorem |
| `test_factors.py` (24) | Euler contributions sum to systematic variance; PCA explains 1.0; single-factor synthetic → PC1 > 95%; saturation flagged |
| `test_rebalance.py` (24) | Zero cost ⇒ pure optimum; prohibitive cost ⇒ no trade; band suppression; lot rounding; impact scales as `Q^1.5`; basket payload shape |
| `test_portfolio_api.py` (56) | All 13 endpoints, 400/422/503 paths, `503` when components are unwired |
| `PortfolioRiskE2ETest` | Through the gateway, ending with the rebalancer's basket accepted by execution-engine (202) |

---

## 10. Limitations

- **`μ` is equilibrium, not forecast.** No alpha model feeds expected returns. Wiring the ML
  signal service in as a Black–Litterman view is the natural follow-up.
- **The fundamental factor model saturates** at six assets. It needs a wider universe to say
  anything the PCA does not.
- **No cash ledger**, so NAV is `configured base + marked gross exposure`. Every response says so
  via `navSource`.
- **`Σ` is estimated from intraday bars and √t-scaled**, inheriting the i.i.d. assumption.
- **`config/portfolio.yml` duplicates** sector/beta/ADV/volatility from the execution-engine's
  `application.yml`. Cross-referenced by comment and pinned by a test; unifying them is a
  separate cross-service refactor.
