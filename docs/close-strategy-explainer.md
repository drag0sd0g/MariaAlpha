# Close Strategy: Concepts and MariaAlpha Implementation

## 1. What Is the Close Algorithm?

The **Close** algorithm targets the official **closing-auction price** as its benchmark. It is the routine choice for any flow whose mark-to-market is the closing print — index-fund tracking, ETF creations/redemptions, mutual-fund net-asset-value flows, and any client order tagged "MOC" (Market-on-Close).

A pure Market-on-Close order is the simplest implementation: submit a single MOC at the exchange's MOC cutoff (NYSE: 15:50 ET; Nasdaq: 15:55 ET) and accept the closing auction's clearing price. The downside is **execution-risk concentration**: the whole parent prints at one price, with no opportunity to dampen impact on the auction itself when the order is large relative to the closing book.

The MariaAlpha implementation is a **working-into-the-close** variant: a configurable `preCloseFraction` of the parent is worked as TWAP-style LIMIT slices through a pre-close window, and the remainder fires as a single MARKET (MOC-equivalent) child at `mocCutoff = closeTime − mocOffsetMinutes`. The pre-close working **dampens impact** on the closing auction; the MOC clip **captures** the close.

```
parent = preCloseFraction × parent   ← worked across N LIMIT slices in [windowStart, mocCutoff)
       + (1 − preCloseFraction) × parent   ← single MARKET at mocCutoff
```

## 2. Why Work Into the Close?

The trade-off the algorithm dials is **closing-print tracking vs. closing-auction impact**:

- A **larger** `preCloseFraction` (e.g. 0.50) spreads more of the parent across the pre-close window. The visible MOC clip is smaller, so the closing auction's clearing price is less likely to be moved by your own order — at the cost of accepting pre-close prices for half the parent, which can drift from the eventual closing print.
- A **smaller** `preCloseFraction` (e.g. 0.10) trades more aggressively at the close. Better tracking against the closing benchmark, but a bigger MOC clip can dislocate the auction itself.

Picking the dial right depends on how big your parent is relative to typical closing-auction volume and how aggressive the benchmark is:

- **Small parents on liquid names** → pure MOC (`preCloseFraction = 0`) is fine; the auction absorbs you without trouble.
- **Medium parents** → 20–40 % pre-close working is the conventional setting.
- **Large parents** (more than ~5 % of closing-auction volume) → higher pre-close fraction, possibly with a separate POV layer for the working portion.

The MariaAlpha implementation supports the full spectrum from pure MOC to pure pre-close working via the single `preCloseFraction` knob.

## 3. The Close Algorithm

Given:
- **Parent order**: BUY 10,000 shares of AAPL
- **Window start**: 15:30 ET
- **Close time**: 16:00 ET
- **MOC offset**: 5 minutes → `mocCutoff = 15:55`
- **Pre-close fraction**: 30 %
- **Pre-close slices**: 6

### Step 1: Build the Pre-Close Schedule

The pre-close window `[windowStart, mocCutoff)` is divided into `numPreCloseSlices` equal-duration intervals. With 6 slices spanning 25 minutes, each slice is ~4 min 10 s. Boundaries are computed in whole seconds from the window start so the final boundary lands exactly on `mocCutoff`:

| Slice | Time            | Allocation |
|-------|-----------------|-----------:|
| 0     | 15:30 – 15:34   | 500        |
| 1     | 15:34 – 15:38   | 500        |
| 2     | 15:38 – 15:42   | 500        |
| 3     | 15:42 – 15:46   | 500        |
| 4     | 15:46 – 15:50   | 500        |
| 5     | 15:50 – 15:55   | 500        |
| **MOC** | **15:55**     | **7,000**  |
|       |                 | **10,000** |

`preCloseTotal = round(0.30 × 10000) = 3000`; per-slice = 500; the last slice absorbs any rounding remainder. `mocAllocation = 10000 − 3000 = 7000`.

### Step 2: Work the Pre-Close Window

As the market clock (driven by **tick timestamps**, not the wall clock) crosses into a new slice, the algorithm emits one **LIMIT** child for that slice's allocation at the current best ask (BUY) or best bid (SELL). Each slice fires **at most once** — re-entering an already-executed slice produces no new signal.

### Step 3: Fire the MOC at the Cutoff

When the clock reaches `mocCutoff`, a single **MARKET** child sweeps **everything not yet emitted** — both the planned MOC clip *and* any skipped pre-close slice quantity. This guarantees the parent leaves the strategy in one shot, even if a few slices were missed because the tape was quiet during their window.

### Step 4: Defensive Post-Close Sweep

If, when the clock reaches `closeTime`, the MOC somehow never fired (e.g. the strategy was bound after `closeTime`), a final MARKET catch-up sweeps any residual. In normal operation this path is dormant; it exists so a late parent does not strand.

### Rounding

Per-slice allocations sum to `preCloseTotal`; the **last pre-close slice absorbs the remainder** so the equation `sum(sliceAllocations) + mocAllocation = targetQuantity` holds exactly for every (parent, fraction, slice-count) combination. A `preCloseFraction` outside `[0, 1]` is clamped at the boundaries — `1.5` ⇒ `1.0` (pure working), `−0.5` ⇒ `0.0` (pure MOC).

## 4. Close in MariaAlpha

### Architecture

Close sits in the `strategy-engine` module and implements the same pluggable `TradingStrategy` interface as the other algorithms, so it is auto-discovered as a Spring `@Component` and registered in the `StrategyRegistry` with **no wiring changes** anywhere else in the pipeline:

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
             |    +-- PovStrategy             |
             |    +-- CloseStrategy           |
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

The `SymbolStrategyRouter` binds a symbol to a strategy name at runtime; the `StrategyEvaluationService` calls `onTick` / `evaluate` and applies the same ML signal confirmation/veto logic to Close signals as it does to the others. Metrics (`mariaalpha_strategy_signals_total`, `mariaalpha_strategy_evaluation_duration_ms`) are already tagged by `strategy`, so Close shows up in Prometheus/Grafana automatically under `strategy="CLOSE"`.

### Parameters

| Parameter | Type | Default | Meaning |
|-----------|------|---------|---------|
| `targetQuantity` | int | 0 | Total parent quantity to execute |
| `side` | `BUY` \| `SELL` | `BUY` | Order side |
| `windowStart` | `HH:mm[:ss]` (ET) | `15:30` | Start of the pre-close working window |
| `closeTime` | `HH:mm[:ss]` (ET) | `16:00` | Regular session close (the benchmark instant) |
| `mocOffsetMinutes` | int | 5 | Minutes before `closeTime` at which the MOC fires |
| `preCloseFraction` | double (0.0 – 1.0) | 0.30 | Share of parent worked in the pre-close window |
| `numPreCloseSlices` | int | 6 | Number of equal-duration pre-close slices |

`getParameters()` additionally returns the derived `preCloseSlices` list, the computed `sliceAllocations` and `mocAllocation` (so the working/MOC split is observable), and live `executedPreCloseSlices` / `totalPreCloseSlices` / `mocFired` counters.

Configure at runtime via REST (parameters are addressed by **strategy name**, binding by **symbol**):

```bash
# Bind Close to AAPL
curl -X PUT -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
    -d '{"strategyName":"CLOSE"}' \
    http://localhost:8080/api/strategies/AAPL

# Configure: 10,000 shares, work 30 % across 6 slices in the last 25 minutes, MOC the rest
curl -X PUT -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
    -d '{"targetQuantity":10000,"side":"BUY","windowStart":"15:30:00","closeTime":"16:00:00","mocOffsetMinutes":5,"preCloseFraction":0.30,"numPreCloseSlices":6}' \
    http://localhost:8080/api/strategies/CLOSE/parameters

# Inspect the computed pre-close/MOC split and live progress
curl -fsS -H "X-API-Key: local-dev-key" \
    http://localhost:8080/api/strategies/CLOSE/parameters
```

### Market Time

Like the other slicing strategies, Close uses **tick timestamps** as its clock source (never `System.currentTimeMillis()`), converted to `America/New_York`. This makes the strategy fully deterministic and replayable: a unit test can simulate the whole afternoon by feeding ticks with chosen timestamps, and historical replay behaves exactly as live trading would have.

### Close vs. the Other Execution Strategies

| Property | VWAP | TWAP | IS | POV | **Close** |
|----------|------|------|----|-----|-----------|
| Benchmark | Day VWAP | Day TWAP | Arrival price | Tape participation | **Closing print** |
| Schedule source | Historical volume profile | Equal time slices | Front-loaded equal slices | Reactive — live tape | **Pre-close TWAP + single MOC** |
| Active window | Full session | Full session | Full session | Full session | **Last ~30 min of session** |
| Final clip | MARKET sweep | MARKET sweep | MARKET sweep | MARKET sweep | **MARKET at MOC cutoff** |
| Best when | Volume shape is stable | Even spacing suffices | Arrival benchmark, alpha decays | Volume shape is uncertain | **Benchmark is the closing price** |

The five are kept as separate, self-contained classes (each in its own package) rather than sharing an abstract base, matching the existing repo convention that every `TradingStrategy` is independently discoverable and reads top-to-bottom without indirection.

### Relationship to Other Components

| Component | Role in Close |
|-----------|---------------|
| **Market Data Gateway** | Provides live ticks (price, bid/ask) that drive the strategy clock and price the pre-close LIMITs |
| **ML Signal Service** | Can suppress a Close child order when a high-confidence signal contradicts the side |
| **Execution Engine** | Receives `OrderSignal`s, applies risk checks + SOR, submits to the exchange. For the MARKET MOC child the executed price represents the closing print (or its simulated equivalent in the simulated profile) |
| **TCA (Post-Trade)** | Measures slippage and tracking error against the **closing-print benchmark** — the very quantity this algorithm sets out to minimize |

### Limitations of the Current Implementation

These mirror the other execution algorithms' MVP limitations and are deliberate scope cuts:

1. **MARKET-as-MOC** — the simulated exchange has no native MOC order type, so the MOC clip is a plain MARKET order with the simulator's normal fill semantics. Against Alpaca paper trading the behaviour is the same. A production MOC handler would route to an exchange-specific MOC order type (NYSE `T` time-in-force, Nasdaq `MOC`) with the appropriate cutoff handling.
2. **No fill tracking / re-planning** — the strategy assumes each child fully fills; it does not reconcile against actual fills to redistribute the unexecuted balance across remaining slices. A partial fill of an early pre-close clip silently widens the MOC.
3. **Static schedule** — the pre-close working schedule is fixed at parameter-update time. A production Close algorithm would adapt the per-slice clip sizes to live volume (e.g. POV-like within the pre-close window) and reduce slices if the strategy starts mid-window.
4. **No participation cap** — there is no "max % of pre-close volume" throttle. On thin names a 30 % working schedule can over-trade relative to the tape; the MOC sweep is the only catch-up mechanism.
5. **No `mocOffsetSeconds`** — the offset is measured in whole minutes, matching real-world MOC cutoff conventions. Sub-minute granularity is not needed for the live algorithm but means unit tests with sub-minute windows have to rely on `preCloseFraction = 0` for pure-MOC scenarios.

## 5. Testing

- **Unit** — `CloseStrategyTest` covers the pre-close LIMIT slice schedule + allocations (equal split, last-slice remainder, sum-to-target invariant), the MOC sweep at the cutoff (including residual + planned-MOC merge and once-only firing), the pure-MOC (`preCloseFraction = 0`) and pure-working (`preCloseFraction = 1`) extremes, `preCloseFraction` clamping outside `[0, 1]`, buy/sell price resolution, trade-price fallback, the post-close defensive sweep for late binding, late-binding-after-cutoff MOC firing, parameter round-trip + state reset, partial parameter updates, a full-trading-day simulation, and the degenerate schedules (inverted window, zero slices, MOC offset overshooting the window).
- **Integration** — `TickConsumerIntegrationTest` publishes a 15:05 ET QUOTE tick to `market-data.ticks` over a Kafka Testcontainer and asserts the CLOSE strategy emits its first pre-close LIMIT slice (100 shares of a 1000-share parent at 50 % pre-close fraction over 5 slices) on `strategy.signals` with `strategy = "CLOSE"`.
- **End-to-end** — `SimulatedHappyPathE2ETest` binds `NVDA → CLOSE` over the full Docker Compose stack with `preCloseFraction = 0` (pure MOC). The simulated CSV NVDA ticks land at 15:55-15:59 ET (≥ mocCutoff 15:55), so the strategy fires the full 45-share parent as a single MARKET (MOC-equivalent) order; the test asserts the resulting position and the persisted MARKET order carry `strategy = "CLOSE"`.

## 6. Further Reading

- **Madhavan (2000)**: "Market Microstructure: A Survey" — comprehensive coverage of auction mechanics including the closing auction.
- **Kissell & Glantz (2003)**: "Optimal Trading Strategies" — covers Close / MOC algorithms alongside VWAP / TWAP / IS.
- **Johnson (2010)**: "Algorithmic Trading & DMA" — practical guide to MOC behaviour across major exchanges including NYSE / Nasdaq cutoff semantics.
- [`twap-strategy-explainer.md`](twap-strategy-explainer.md) — the equal-time-slicing schedule that the Close strategy's pre-close working portion mirrors.
- [`vwap-strategy-explainer.md`](vwap-strategy-explainer.md), [`implementation-shortfall-strategy-explainer.md`](implementation-shortfall-strategy-explainer.md), [`pov-strategy-explainer.md`](pov-strategy-explainer.md) — the four other execution strategies Close sits alongside in the registry.
- [`tca-methodology.md`](tca-methodology.md) — how slippage against the closing benchmark is measured post-trade.
