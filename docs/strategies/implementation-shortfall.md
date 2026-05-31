# Implementation Shortfall Strategy: Concepts and MariaAlpha Implementation

## 1. What Is Implementation Shortfall?

**Implementation Shortfall (IS)** is, first, a *measure*: the difference between the **decision (arrival) price** — the mid-price at the moment you decided to trade — and the **realized average price** you actually achieved, including any unexecuted remainder marked at the close. It is the most complete single number for "what did this trade really cost me?", capturing both:

- **Market impact / spread cost** — the price moves you cause and the spread you pay by trading.
- **Timing risk (opportunity cost)** — the price drifting away from your arrival mark while you wait to execute.

Second, IS is the name of an *execution algorithm*: one that schedules a parent order to **minimize that shortfall**. The defining behaviour is **front-loading** — trade more aggressively early, less later — which is exactly what MariaAlpha implements.

```
Implementation Shortfall = (avg fill price − arrival price) × side   (for a buy, positive = worse)
```

This is the same metric the Post-Trade engine already reports per order (`impl_shortfall_bps` in `tca_results`); the IS *algorithm* is the execution-side counterpart that tries to make that number small.

## 2. Why Front-Load? The Impact-vs-Timing Trade-off

Every execution algorithm trades off two opposing costs:

- Trade **fast** → high **market impact** (you move the price against yourself), but low **timing risk** (little time for the market to drift).
- Trade **slow** → low market impact, but high timing risk (the longer you take, the more the price can wander away from your arrival mark).

VWAP and TWAP sit toward the patient end — they spread an order across the whole window to minimize impact, implicitly accepting timing risk. **Implementation Shortfall deliberately leans the other way**: it front-loads, accepting somewhat higher impact in exchange for capturing more of the order near the arrival price and shrinking exposure to adverse drift. You reach for IS when:

- **The arrival price is the benchmark you're judged against** (classic buy-side "get me close to where I decided"), rather than the day's VWAP/TWAP.
- **You have a directional view / alpha that decays** — waiting forfeits the very edge that motivated the trade.
- **Volatility / timing risk is high** relative to your size, so the cost of waiting dominates the cost of impact.

The aggressiveness is a dial, not a fixed choice — see `urgency` below.

## 3. The IS Slicing Algorithm

Given:
- **Parent order**: BUY 100,000 shares of AAPL
- **Trading window**: 09:30 to 16:00 ET
- **Number of slices**: `numSlices = 6` (65-minute slices)
- **Urgency**: `urgency = 0.5`

### Step 1: Build an Equal-Duration Schedule

Identical to TWAP — the window is divided into `numSlices` equal intervals. Boundaries are computed in whole seconds **from the window start** (`start + round(i × total / N)`) so they never drift and the last boundary lands exactly on `endTime`.

### Step 2: Front-Load the Allocations (Almgren–Chriss)

This is the **only** thing that differs from TWAP. Instead of splitting the parent equally, IS allocates along the **Almgren–Chriss optimal-execution trajectory**. The fraction of the parent still outstanding after slice `i` follows

```
h(i) = sinh(κ·(N − i)) / sinh(κ·N)
```

so slice `i` trades `h(i) − h(i+1)`, where **κ = `urgency`** is a dimensionless aggressiveness parameter. These weights are strictly decreasing for κ > 0 and always sum to 1. For our example:

| Slice | Time | `urgency = 0` (TWAP) | `urgency = 0.5` | `urgency = 1.0` |
|-------|------|----------------------|-----------------|-----------------|
| 0 | 09:30-10:35 | 16.7% | **39.6%** | 63.2% |
| 1 | 10:35-11:40 | 16.7% | 24.2% | 23.3% |
| 2 | 11:40-12:45 | 16.7% | 14.9% | 8.6% |
| 3 | 12:45-13:50 | 16.7% | 9.5% | 3.2% |
| 4 | 13:50-14:55 | 16.7% | 6.5% | 1.2% |
| 5 | 14:55-16:00 | 16.7% | 5.2% | 0.6% |

As **κ → 0** every weight tends to `1/N` — i.e. IS degrades **exactly to TWAP**. The larger κ, the more of the order is packed into the first few slices. (A non-positive `urgency` is treated as the uniform/TWAP case directly, sidestepping the `sinh(0)` singularity.)

> The hyperbolic sines are evaluated in their numerically stable exponential form (dividing through by `e^{κN}/2`), so the computation never overflows even for large `κ·N`.

### Step 3: Execute Each Slice

As the market clock (driven by **tick timestamps**, not the wall clock) crosses into a new slice, the algorithm emits one **child order** for that slice's allocation: a **LIMIT** order at the current best ask (buys) or best bid (sells). Each slice fires **at most once**.

### Step 4: End-of-Day Sweep

If unexecuted quantity remains when the clock reaches `endTime` (e.g. the strategy started mid-window), a single final **MARKET** order sweeps the remainder so the parent is fully executed by the deadline.

### Rounding

Each slice receives `round(targetQuantity × weight)`; the **last slice absorbs the remainder** so the per-slice allocations always sum exactly to the target.

## 4. Implementation Shortfall in MariaAlpha

### Architecture

IS sits in the `strategy-engine` module and implements the same pluggable `TradingStrategy` interface as VWAP/TWAP/Momentum, so it is auto-discovered as a Spring `@Component` and registered in the `StrategyRegistry` with **no wiring changes** anywhere else in the pipeline:

```
                  market-data.ticks (Kafka)
                         |
                         v
             +------- Strategy Engine -------+
             |                               |
             |  TradingStrategy interface     |
             |    +-- VwapStrategy            |
             |    +-- TwapStrategy            |
             |    +-- MomentumStrategy        |
             |    +-- ImplementationShortfall |
             |                               |
             |  StrategyRegistry              |
             |    auto-discovers @Component   |
             |    strategies via Spring DI    |
             |                               |
             +-------------------------------+
                         |
                         v
                    OrderSignal  --> strategy.signals (Kafka)
                         |
                         v
                  Execution Engine
```

The `SymbolStrategyRouter` binds a symbol to a strategy name at runtime; the `StrategyEvaluationService` calls `onTick` / `evaluate` and applies the same ML signal confirmation/veto logic to IS signals as it does to the others. Metrics (`mariaalpha_strategy_signals_total`, `mariaalpha_strategy_evaluation_duration_ms`) are already tagged by `strategy`, so IS shows up in Prometheus/Grafana automatically under `strategy="IS"`.

### Parameters

| Parameter | Type | Default | Meaning |
|-----------|------|---------|---------|
| `targetQuantity` | int | 0 | Total parent quantity to execute |
| `side` | `BUY` \| `SELL` | `BUY` | Order side |
| `startTime` | `HH:mm[:ss]` (ET) | `09:30` | Window start (US Eastern) |
| `endTime` | `HH:mm[:ss]` (ET) | `16:00` | Window end (US Eastern) |
| `numSlices` | int | 12 | Number of equal time slices |
| `urgency` | double (κ ≥ 0) | 0.5 | Front-loading aggressiveness; `0` = TWAP, higher = more front-loaded |

`getParameters()` additionally returns the derived `slices` list, the computed `allocations` (per-slice share counts, so the front-load shape is observable), and live `executedSlices` / `totalSlices` counters.

Configure at runtime via REST (parameters are addressed by **strategy name**, binding by **symbol**):

```bash
# Bind Implementation Shortfall to AAPL
curl -X PUT -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
    -d '{"strategyName":"IS"}' \
    http://localhost:8080/api/strategies/AAPL

# Configure: 100 shares, 6 front-loaded slices, urgency 0.5, 14:30-16:00 ET
curl -X PUT -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
    -d '{"targetQuantity":100,"side":"BUY","startTime":"14:30:00","endTime":"16:00:00","numSlices":6,"urgency":0.5}' \
    http://localhost:8080/api/strategies/IS/parameters

# Inspect the computed front-loaded allocations
curl -fsS -H "X-API-Key: local-dev-key" \
    http://localhost:8080/api/strategies/IS/parameters
```

### Market Time

Like VWAP/TWAP, IS uses **tick timestamps** as its clock source (never `System.currentTimeMillis()`), converted to `America/New_York`. This makes the strategy fully deterministic and replayable: a unit test can simulate an entire trading day by feeding ticks with chosen timestamps, and historical replay behaves exactly as live trading would have.

### IS vs. TWAP vs. VWAP in the Codebase

`ImplementationShortfallStrategy` is intentionally a near-mirror of `TwapStrategy` — same `onTick`/`evaluate` shape, same at-most-once-per-slice guard (`ConcurrentHashMap.putIfAbsent`), same LIMIT-per-slice + MARKET-sweep execution, same bid/ask price resolution, same equal-duration schedule. The **only** algorithmic difference is the per-slice allocation:

- **VWAP** allocates proportional to an explicit historical `volumeProfile`.
- **TWAP** allocates **uniformly** (`1/N` per slice).
- **IS** allocates along a **front-loaded** Almgren–Chriss curve governed by `urgency`; at `urgency = 0` it *is* TWAP.

The three are kept as separate, self-contained classes (each in its own package) rather than sharing an abstract base, matching the existing repo convention that every `TradingStrategy` is independently discoverable and reads top-to-bottom without indirection.

### Relationship to Other Components

| Component | Role in IS |
|-----------|------------|
| **Market Data Gateway** | Provides live ticks (price, bid/ask) that drive the strategy clock |
| **ML Signal Service** | Can suppress an IS child order when a high-confidence signal contradicts the side |
| **Execution Engine** | Receives `OrderSignal`s, applies risk checks + SOR, submits to the exchange |
| **TCA (Post-Trade)** | Measures the achieved `impl_shortfall_bps` against the arrival price — the very quantity this algorithm sets out to minimize |

### Limitations of the Current Implementation

These mirror VWAP/TWAP's MVP limitations and are deliberate scope cuts:

1. **Static schedule from a single urgency knob** — a production IS would derive κ from a live volatility / impact model (`κ = √(λσ²/η)` in the Almgren–Chriss framework) and re-solve intraday, rather than taking `urgency` as a fixed input.
2. **No fill tracking / re-planning** — the strategy assumes each child fully fills; it does not reconcile against actual fills to redistribute the unexecuted balance across remaining slices.
3. **One signal per slice** — a production IS would subdivide each slice into smaller, randomized wavelets to reduce predictability.
4. **No participation cap** — there is no "max % of interval volume" throttle, so on thin names an aggressively front-loaded first slice can over-trade relative to the tape. The end-of-window MARKET sweep is the only catch-up mechanism.

## 5. Testing

- **Unit** — `ImplementationShortfallStrategyTest` covers the front-loaded allocation (strictly decreasing, sums to target, last-slice remainder), the `urgency = 0` ⇒ TWAP equivalence and the higher-κ-concentrates-earlier property, at-most-once per slice, the end-of-window sweep (including "nothing to sweep" and "sweep only once"), buy/sell price resolution, trade-price fallback, parameter round-trip + state reset, partial parameter updates, a full-trading-day simulation, and degenerate schedules (inverted window, zero slices).
- **Integration** — `TickConsumerIntegrationTest` publishes a tick to `market-data.ticks` over a Kafka Testcontainer and asserts the IS strategy emits its **front-loaded** first-slice allocation (557 of 1000 shares over two slices, vs. the 500 a uniform split would give) on `strategy.signals` with `strategy = "IS"`.
- **End-to-end** — `SimulatedHappyPathE2ETest` binds `AMZN → IS` over the full Docker Compose stack and asserts the resulting position fills and the persisted order carries `strategy = "IS"`.

## 6. Further Reading

- **Perold (1988)**: "The Implementation Shortfall: Paper versus Reality" — the paper that coined the term.
- **Almgren & Chriss (2000)**: "Optimal Execution of Portfolio Transactions" — the optimal-trajectory framework whose `sinh` holdings curve this strategy front-loads along.
- **Kissell & Glantz (2003)**: "Optimal Trading Strategies" — comprehensive coverage of VWAP, TWAP, and implementation shortfall algorithms.
- **Johnson (2010)**: "Algorithmic Trading & DMA" — practical guide to execution algorithms including IS.
- [`twap.md`](twap.md) — the sibling execution strategy this one mirrors (IS at `urgency = 0`).
- [`vwap.md`](vwap.md) — the volume-profile execution strategy.
- [`momentum.md`](momentum.md) — the trend-following *alpha* strategy that complements these *execution* algorithms.
- [`tca-methodology`](../guides/tca-methodology.md) — how `impl_shortfall_bps` is measured post-trade, closing the loop on what IS optimizes.
