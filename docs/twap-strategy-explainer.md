# TWAP Strategy: Concepts and MariaAlpha Implementation

## 1. What Is TWAP?

**TWAP** (Time-Weighted Average Price) is the benchmark price calculated as the simple average of prices sampled at regular intervals over a period:

```
TWAP = SUM(Price_i) / N
```

Where each `i` is one of `N` equally-spaced time samples during the period. Unlike VWAP, the samples are **not** weighted by volume — every interval counts the same regardless of how much actually traded in it.

TWAP serves the same two purposes as VWAP:

1. **As a benchmark**: a time-weighted reference an execution can be measured against.
2. **As an execution algorithm**: an algorithm that slices a large parent order into equal child orders spread evenly across time, so the average fill price tracks the time-weighted average. This is what MariaAlpha implements.

## 2. Why Use TWAP Execution?

Like VWAP, TWAP exists to work a large order into the market without the impact of a single block trade. The difference is **how** the parent is sliced: TWAP ignores the volume profile and simply spreads the order **uniformly over time**.

That trade-off is deliberate and is exactly when you reach for TWAP over VWAP:

- **Illiquid names / no reliable volume profile** — if you can't trust a historical volume curve (thin stock, new listing, unusual session), VWAP's core input is garbage. TWAP needs no profile.
- **Overnight / extended sessions** — volume patterns break down outside regular trading hours; even spacing is the safer default.
- **Predictability and simplicity** — the schedule is trivial to reason about ("a slice every five minutes"), which matters for compliance, client communication, and debugging.
- **Lower information leakage from profile-chasing** — TWAP does not lean into the open/close volume spikes the way VWAP does, so its footprint is flatter and more uniform.

The cost is that TWAP can trade *against* thin liquidity (it will try to do its scheduled slice even when little is trading), so its market impact can be **higher** than VWAP on names with a strong, predictable volume shape. See the comparison table in [`vwap-strategy-explainer.md` §6](vwap-strategy-explainer.md#6-vwap-vs-twap).

## 3. The TWAP Slicing Algorithm

Given:
- **Parent order**: BUY 100,000 shares of AAPL
- **Trading window**: 09:30 to 16:00 ET (6h30m = 23,400 seconds)
- **Number of slices**: `numSlices = 13`

### Step 1: Build an Equal-Duration Schedule

The window is divided into `numSlices` equal intervals. With 13 slices over 23,400 seconds, each slice is 1,800 s (30 minutes):

| Slice | Time | Allocation (of 100,000) |
|-------|------|-------------------------|
| 0 | 09:30-10:00 | 7,692 |
| 1 | 10:00-10:30 | 7,692 |
| … | … | … |
| 11 | 15:00-15:30 | 7,692 |
| 12 | 15:30-16:00 | 7,696 (absorbs remainder) |
| | | **100,000** |

Boundaries are computed in whole seconds **from the window start** (`start + round(i × total / N)`), not by repeatedly adding a rounded slice width. This keeps the boundaries from drifting and guarantees the final boundary lands exactly on `endTime`.

### Step 2: Execute Each Slice

As the market clock (driven by **tick timestamps**, not the wall clock) crosses into a new slice, the algorithm emits one **child order** for that slice's allocation. The child is a **LIMIT** order at the current best ask (buys) or best bid (sells), giving price protection. Each slice fires **at most once** — re-entering an already-executed slice produces no new signal.

### Step 3: End-of-Day Sweep

If, when the clock reaches `endTime`, there is still unexecuted quantity (e.g. the strategy was started mid-window, or some slices were skipped), the algorithm emits a single final **MARKET** order to sweep the remainder so the parent is fully executed by the deadline.

### Rounding

Each slice receives `round(targetQuantity / numSlices)`; the **last slice absorbs the remainder** so the per-slice allocations always sum exactly to the target. For example, 1,001 shares over 3 slices → 334, 334, 333.

## 4. TWAP in MariaAlpha

### Architecture

TWAP sits in the `strategy-engine` module and implements the same pluggable `TradingStrategy` interface as VWAP, so it is auto-discovered as a Spring `@Component` and registered in the `StrategyRegistry` with **no wiring changes** anywhere else in the pipeline:

```
                  market-data.ticks (Kafka)
                         |
                         v
             +------- Strategy Engine -------+
             |                               |
             |  TradingStrategy interface     |
             |    +-- VwapStrategy            |
             |    +-- TwapStrategy            |
             |    +-- MomentumStrategy (fut.) |
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

The `SymbolStrategyRouter` binds a symbol to a strategy name at runtime; the `StrategyEvaluationService` calls `onTick` / `evaluate` and applies the same ML signal confirmation/veto logic to TWAP signals as it does to VWAP. Metrics (`mariaalpha_strategy_signals_total`, `mariaalpha_strategy_evaluation_duration_ms`) are already tagged by `strategy`, so TWAP shows up in Prometheus/Grafana automatically under `strategy="TWAP"`.

### Parameters

| Parameter | Type | Default | Meaning |
|-----------|------|---------|---------|
| `targetQuantity` | int | 0 | Total parent quantity to execute |
| `side` | `BUY` \| `SELL` | `BUY` | Order side |
| `startTime` | `HH:mm[:ss]` (ET) | `09:30` | Window start (US Eastern) |
| `endTime` | `HH:mm[:ss]` (ET) | `16:00` | Window end (US Eastern) |
| `numSlices` | int | 12 | Number of equal time slices |

`getParameters()` additionally returns the derived `slices` list and live `executedSlices` / `totalSlices` counters for monitoring.

Configure at runtime via REST (parameters are addressed by **strategy name**, binding by **symbol**):

```bash
# Bind TWAP to AAPL
curl -X PUT -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
    -d '{"strategyName":"TWAP"}' \
    http://localhost:8080/api/strategies/AAPL

# Configure: 100 shares, 6 equal slices, 14:30-16:00 ET
curl -X PUT -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
    -d '{"targetQuantity":100,"side":"BUY","startTime":"14:30:00","endTime":"16:00:00","numSlices":6}' \
    http://localhost:8080/api/strategies/TWAP/parameters
```

### Market Time

Like VWAP, TWAP uses **tick timestamps** as its clock source (never `System.currentTimeMillis()`), converted to `America/New_York`. This makes the strategy fully deterministic and replayable: a unit test can simulate an entire trading day by feeding ticks with chosen timestamps, and historical replay behaves exactly as live trading would have.

### TWAP vs. VWAP in the Codebase

`TwapStrategy` is intentionally a near-mirror of `VwapStrategy` — same `onTick`/`evaluate` shape, same at-most-once-per-interval guard (`ConcurrentHashMap.putIfAbsent`), same LIMIT-per-interval + MARKET-sweep execution, same bid/ask price resolution. The **only** algorithmic difference is the schedule:

- VWAP takes an explicit `volumeProfile` (a list of `TimeBin`s with volume fractions) and allocates proportionally.
- TWAP computes its own equal-duration `TwapSlice`s from `numSlices` and allocates uniformly.

The two are kept as separate, self-contained classes (each in its own package) rather than sharing an abstract base, matching the existing repo convention that every `TradingStrategy` is independently discoverable and reads top-to-bottom without indirection.

### Relationship to Other Components

| Component | Role in TWAP |
|-----------|--------------|
| **Market Data Gateway** | Provides live ticks (price, bid/ask) that drive the strategy clock |
| **ML Signal Service** | Can suppress a TWAP child order when a high-confidence signal contradicts the side |
| **Execution Engine** | Receives `OrderSignal`s, applies risk checks + SOR, submits to the exchange |
| **TCA (Post-Trade)** | Measures achieved fill price against benchmarks for each completed order |

### Limitations of the Current Implementation

These mirror VWAP's MVP limitations and are deliberate scope cuts:

1. **One signal per slice** — a production TWAP would subdivide each slice into smaller wavelets and randomise timing/size to reduce predictability.
2. **No fill tracking** — the strategy assumes each child fully fills; it does not yet reconcile against actual fills to re-plan remaining slices.
3. **No participation cap** — there is no "max % of interval volume" throttle, so on very thin names a slice can still over-trade relative to the tape. (This is precisely the risk that makes TWAP higher-impact than VWAP.)
4. **No catch-up/slow-down logic** — if the strategy starts mid-window it does not redistribute the skipped quantity across the remaining slices; the leftover is handled by the end-of-window MARKET sweep.

## 5. Testing

- **Unit** — `TwapStrategyTest` covers slice scheduling, equal allocation with last-slice remainder, at-most-once per slice, the end-of-window sweep (including "nothing to sweep" and "sweep only once"), buy/sell price resolution, trade-price fallback, parameter round-trip + state reset, partial parameter updates, a full-trading-day simulation, and degenerate schedules (inverted window, zero slices).
- **Integration** — `TickConsumerIntegrationTest` publishes a tick to `market-data.ticks` over a Kafka Testcontainer and asserts a `TWAP` `OrderSignal` lands on `strategy.signals`.
- **End-to-end** — `SimulatedHappyPathE2ETest` binds `MSFT → TWAP` over the full Docker Compose stack and asserts the resulting position fills and the persisted order carries `strategy = "TWAP"`.

## 6. Further Reading

- **Almgren & Chriss (2000)**: "Optimal Execution of Portfolio Transactions" — foundational paper on optimal trade execution.
- **Kissell & Glantz (2003)**: "Optimal Trading Strategies" — comprehensive coverage of VWAP, TWAP, and implementation shortfall algorithms.
- **Johnson (2010)**: "Algorithmic Trading & DMA" — practical guide to execution algorithms including TWAP.
- [`vwap-strategy-explainer.md`](vwap-strategy-explainer.md) — the sibling strategy this one mirrors.
