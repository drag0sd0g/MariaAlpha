# Options Pricing (Black-Scholes-Merton + Greeks)

> **Roadmap:** [3.2.1 — Black-Scholes pricing model](https://github.com/drag0sd0g/MariaAlpha/issues/90), [3.2.2 — Greeks computation](https://github.com/drag0sd0g/MariaAlpha/issues/91).
> **TDD reference:** §5.2.2 (Strategy Engine).

## 1. What this is

The options pricing module is a small, self-contained subsystem inside `strategy-engine` that prices a single European option contract on a single underlying and reports the first-order Greek sensitivities. It is the foundation that future option strategies, derivatives risk checks, and the eventual IBKR-backed options surface will all build on.

It is intentionally narrow:

- **European-style only** — exercise at expiry, no American early-exercise modelling. American pricing is a future concern (likely binomial or Bjerksund-Stensland) and would land alongside the IBKR adapter (3.1.1 / 3.1.2).
- **Single name, single contract** — no portfolio aggregation, no multi-leg strategies. Those layer on top.
- **Stateless** — no Kafka topics, no database, no inventory state. Each request is one closed-form calculation, plus optionally a Newton-Raphson solve for implied volatility.

Unlike the equity strategies (VWAP / TWAP / Momentum / IS / POV / Close), it does **not** implement `TradingStrategy`. It exposes itself as REST endpoints under `/api/options/**` and as injectable Spring beans for callers inside the JVM.

---

## 2. The model

We use the **Black-Scholes-Merton** generalisation of the original Black-Scholes formula — i.e. the version that accepts a continuous dividend yield `q`. Setting `q = 0` recovers the textbook equity formulas; `q > 0` is correct for index options (where dividends accrue continuously) and is approximately correct for individual names if you proxy discrete dividends as a continuous yield.

Let

- `S` — spot price of the underlying
- `K` — strike
- `T` — time to expiry, in years
- `σ` — annualised volatility of log-returns
- `r` — continuously-compounded risk-free rate
- `q` — continuous dividend yield
- `Φ(·)` — standard normal cumulative distribution
- `φ(·)` — standard normal probability density

Then

```
d1 = (ln(S/K) + (r − q + σ²/2)·T) / (σ·√T)
d2 = d1 − σ·√T

Call = S·e^(−q·T)·Φ(d1) − K·e^(−r·T)·Φ(d2)
Put  = K·e^(−r·T)·Φ(−d2) − S·e^(−q·T)·Φ(−d1)
```

`Φ(·)` is implemented in `NormalDistribution` using **Abramowitz & Stegun 26.2.17** — a five-term rational approximation accurate to ~7.5×10⁻⁸ everywhere on the real line, which is comfortably tighter than our 4-decimal acceptance criteria.

### 2.1 Greeks

All five first-order sensitivities are computed analytically (no numerical bumps in production). The numerical-bump equivalents are exercised in the test suite as an independent cross-check (`GreeksCalculatorTest#deltaMatchesNumericalBumpForCall` etc.).

```
Δ_call = e^(−q·T)·Φ(d1)
Δ_put  = e^(−q·T)·(Φ(d1) − 1)
Γ      = e^(−q·T)·φ(d1) / (S·σ·√T)
vega   = S·e^(−q·T)·φ(d1)·√T           ÷ 100 → per-1%-vol
Θ_call = −S·e^(−q·T)·φ(d1)·σ/(2·√T) − r·K·e^(−r·T)·Φ(d2) + q·S·e^(−q·T)·Φ(d1)
                                       ÷ thetaDayCount → per-day
Θ_put  = −S·e^(−q·T)·φ(d1)·σ/(2·√T) + r·K·e^(−r·T)·Φ(−d2) − q·S·e^(−q·T)·Φ(−d1)
ρ_call =  K·T·e^(−r·T)·Φ(d2)           ÷ 100 → per-1%-rate
ρ_put  = −K·T·e^(−r·T)·Φ(−d2)
```

#### Scaling conventions

Vega and Rho are reported per **1 percentage point** move in their underlying input (so e.g. vega = 0.088 means "+1% vol adds $0.088 to the premium"). Theta is reported per **one day** using either a 365-calendar-day or 252-trading-day convention, switchable via `strategy-engine.options.theta-day-count`. The default of 365 matches Bloomberg's OVME screen so manual cross-checks against an external pricer line up out of the box.

These conventions are applied **once**, at the response boundary inside `GreeksCalculator.compute(...)` — the raw annualised vega and theta never leak out. Tests that need the raw form (e.g. the implied-vol Newton step) re-derive them from the analytic formula instead.

### 2.2 Implied volatility solver

`ImpliedVolatilityCalculator` inverts the pricer to find the σ that makes the model reproduce an observed market premium. Strategy:

1. **Newton-Raphson** seeded at σ₀ = 0.20 using analytic vega as the derivative. Quadratic convergence in the bulk of the parameter space — typically 3-6 iterations from a 20-vol seed to a 30-vol or 40-vol implied.
2. **Bisection fallback** on `[impliedVolLowerBound, impliedVolUpperBound]` (default `[0.0001, 5.0]`) whenever Newton steps outside the bracket or vega collapses to near-zero (deep-OTM, very near expiry).

Input validation rejects any premium outside the no-arbitrage band:

```
Call:  max(0, S·e^(−q·T) − K·e^(−r·T))  ≤  C  ≤  S·e^(−q·T)
Put:   max(0, K·e^(−r·T) − S·e^(−q·T))  ≤  P  ≤  K·e^(−r·T)
```

Premiums on or outside the band have no implied σ and produce **400 Bad Request**. The response carries the solver method (`NEWTON` / `BISECTION`), iteration count, and final residual so callers can spot poor convergence.

---

## 3. REST endpoints

All three endpoints live under `/api/options/**` and are routed by the api-gateway's existing `strategies` route — no new route bean, no new authentication path.

| Endpoint | Body | Returns |
| --- | --- | --- |
| `POST /api/options/price` | `OptionPricingRequest` | `OptionPricingResponse` (premium + all five Greeks) |
| `POST /api/options/greeks` | `OptionPricingRequest` | `GreeksResponse` (Greeks only — skips the price calc when caller already has it) |
| `POST /api/options/implied-volatility` | `ImpliedVolatilityRequest` | `ImpliedVolatilityResponse` (σ + iterations + method + residual) |

### 3.1 `POST /api/options/price` example

```json
{
  "symbol": "AAPL",
  "spot": 42.0,
  "strike": 40.0,
  "timeToExpiryYears": 0.5,
  "volatility": 0.20,
  "riskFreeRate": 0.10,
  "dividendYield": 0.0,
  "type": "CALL"
}
```

Response:

```json
{
  "symbol": "AAPL",
  "type":   "CALL",
  "price":  4.7594,
  "greeks": {
    "delta":  0.7791,
    "gamma":  0.0499,
    "vega":   0.0879,
    "theta": -0.01247,
    "rho":    0.1397
  }
}
```

(These match Hull's Ch.15 worked example to four decimals — the test suite gates on it.)

### 3.2 `POST /api/options/implied-volatility` example

```json
{
  "symbol": "AAPL",
  "spot": 100.0,
  "strike": 100.0,
  "timeToExpiryYears": 0.5,
  "riskFreeRate": 0.04,
  "dividendYield": 0.0,
  "type": "CALL",
  "marketPrice": 8.42
}
```

Response:

```json
{
  "symbol": "AAPL",
  "type": "CALL",
  "impliedVolatility": 0.2812,
  "iterations": 4,
  "method": "NEWTON",
  "residual": 1.4e-9
}
```

### 3.3 Validation and error codes

- **400 Bad Request** — any invalid input: spot ≤ 0, strike ≤ 0, T ≤ 0, σ ≤ 0 (in `/price` and `/greeks`), q < 0, missing `type`, premium outside no-arbitrage band (in `/implied-volatility`).
- **422 Unprocessable Entity** — reserved for the solver failing to converge from a well-formed input (the bisection bracket disagreed in sign across the configured bounds). Should be vanishingly rare in practice; if you see one in production it indicates the input is right at the limit of the no-arb band.

---

## 4. Configuration

```yaml
strategy-engine:
  options:
    theta-day-count:       365      # 365 = calendar (default, Bloomberg OVME), 252 = trading
    implied-vol-max-iterations: 100
    implied-vol-tolerance: 0.000001  # absolute $ residual
    implied-vol-lower-bound: 0.0001  # σ floor for bisection bracket
    implied-vol-upper-bound: 5.0     # σ ceiling — 500%/yr
```

All values are validated in `OptionsPricingConfig`'s compact constructor and snap to safe defaults if a non-positive value is supplied.

---

## 5. Metrics

| Metric | Type | Labels | Description |
| --- | --- | --- | --- |
| `mariaalpha_options_pricings_total` | Counter | `type=CALL\|PUT` | Total Black-Scholes pricings executed |
| `mariaalpha_options_pricing_duration` | Timer | `type=CALL\|PUT` | Black-Scholes pricing latency |
| `mariaalpha_options_implied_vol_solves_total` | Counter | `type`, `method=NEWTON\|BISECTION` | Implied-vol solves by method |
| `mariaalpha_options_implied_vol_iterations` | Distribution | `type`, `method` | Iterations to converge |

A Grafana panel showing the Newton/bisection method split is the natural follow-up — useful for spotting drift in input quality (e.g. a stale risk-free rate pushing solves into the bisection arm).

---

## 6. Why this lives in `strategy-engine`

Two candidate homes existed:

1. A new `options-pricing` microservice.
2. Inside `strategy-engine`.

The TDD's §5.2.2 already scopes options pricing to the Strategy Engine row, and the actual math is ~250 lines of self-contained code with **zero I/O**, **zero state**, and **zero downstream calls**. Carving a new microservice for that would add operational surface (Helm chart, K8s deployment, metrics scraping, image build) for no architectural benefit. The chosen approach mirrors how `rfq/` lives as a self-contained subdirectory in the same module.

When option-strategies appear later in the roadmap they will likely need exactly this pricer + Greeks for entry/exit signal computation, so co-locating it with the rest of the strategy code is the right shape.

---

## 7. Test coverage

| Test | What it asserts |
| --- | --- |
| `NormalDistributionTest` | Φ and φ match A&S 26.2 reference values; reflection symmetry; tail limits. |
| `OptionContractTest` | Compact-constructor validation across every input. |
| `BlackScholesPricerTest` | Hull Ch.15 textbook values to 0.01; put-call parity with continuous q; ITM/OTM intrinsic limits; monotonicity in σ, T, q. |
| `GreeksCalculatorTest` | Hull textbook Greeks to 0.001; parity-derived put Greeks; delta/gamma/vega match numerical-bump derivatives to 1e-4; Γ ≥ 0; vega ≥ 0; theta day-count convention scaling. |
| `ImpliedVolatilityCalculatorTest` | Round-trip price→σ recovery; no-arbitrage band rejection; convergence in <10 Newton iters for reasonable inputs. |
| `OptionPricingServiceTest` | Façade returns same numbers as the components in isolation; metrics counter + timer fire. |
| `OptionsControllerTest` | REST mapping: bad inputs → 400, good inputs → JSON; implied-vol round-trips through the controller. |
| `OptionsEndToEndIntegrationTest` (integration) | Boots strategy-engine context with Testcontainers Kafka; full MockMvc HTTP path for `/price`, `/greeks`, `/implied-volatility`. |
| `OptionsPricingE2ETest` (e2e) | Full docker-compose stack: Hull textbook value reached through the api-gateway; put-call parity end-to-end; implied-vol round-trip; 400 on bad inputs. |
| UI `Options.test.tsx` | Price+Greeks render with correct formatting; IV solve happy path; API error surfaces. |
