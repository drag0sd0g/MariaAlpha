# Percentage of Volume (POV) Strategy: Concepts and MariaAlpha Implementation

## 1. What Is POV?

**Percentage of Volume (POV)** — also called *Participation Rate* or *Volume Participation* — is an execution algorithm that trades alongside the market as a configurable fraction `p` of the live traded volume. If the tape prints 10,000 shares and `p = 10%`, the strategy targets 1,000 shares executed; if the tape later prints another 5,000 the target rises to 1,500; and so on. The strategy is **reactive**: it does not commit to a schedule up front (the way VWAP/TWAP/IS do) but instead lets the actual flow of the market dictate the cadence of its child orders.

```
target_executed(t) = min(parent_target, p × cumulative_market_volume(t))
delta(t)           = target_executed(t) − already_emitted(t)
```

At every tick the algorithm computes `delta` and, if it clears `minClipSize`, fires a LIMIT child order for that many shares.

## 2. Why Use POV?

POV is the right tool when the **shape of the day is uncertain** and you want your footprint to scale with whatever the market actually does:

- **Adaptive to liquidity** — if the tape is quiet, POV trades less; if a wave of volume hits, POV joins it proportionally. VWAP/TWAP commit to a schedule and can over-trade thin stretches or under-trade busy ones.
- **Lower signalling risk on news / event days** — on days where the volume profile is unknowable in advance (earnings, macro prints, listing day), POV's reactive behaviour avoids the "I told the world I'm here every five minutes" problem of TWAP and the "my profile is wrong" problem of VWAP.
- **Soft-target completion** — POV does *not* guarantee the parent completes within the window. If liquidity dries up the strategy quietly under-trades; the end-of-window MARKET sweep is the safety valve.
- **Bounded clip size** — `minClipSize` collapses micro-emissions into a single tradable lot, and `maxClipSize` prevents a single block print from pulling the strategy off course (the cap takes the remainder on subsequent ticks).

The trade-off vs. the schedule-driven algorithms is **no completion guarantee** inside the window and **dependence on the tape being honest** — on stocks where most of the day's volume comes in a closing-auction print, POV will look idle and then get caught by its own sweep.

## 3. The POV Algorithm

Given:
- **Parent order**: BUY 100,000 shares of AAPL
- **Trading window**: 09:30 to 16:00 ET
- **Participation rate**: `p = 10%`
- **Min clip**: 100 shares; **Max clip**: 5,000 shares

### Step 1: Cumulative Volume Bookkeeping

On every market tick:
- **TRADE event** with `size > 0` in `[startTime, endTime)` → `cumulativeMarketVolume += size`.
- **QUOTE / BAR events**, or trades outside the window → ignored for volume. The latest tick is still cached so quotes can update the bid/ask used for limit-price resolution.

> Only TRADE prints count as "tape volume". Quotes are *intent* (where you could trade), not *executed* volume. Mixing them would systematically over-state participation.

### Step 2: Compute the Desired Already-Traded Total

At every tick, after the volume update:

```
target_executed = min(parent_target, ⌊p × cumulative_market_volume⌋)
delta           = target_executed − already_emitted
```

- `delta < minClipSize` → defer. The clip is too small to be worth working a child order; the deficit rolls forward and merges with the next tick's accumulation.
- `delta ≥ minClipSize` → emit a LIMIT child for `min(delta, maxClipSize, parent_target − already_emitted)` shares.

### Step 3: Fire LIMIT Children at Bid/Ask

Like VWAP/TWAP/IS, child orders are LIMIT at the current best ask (BUY) or best bid (SELL), giving simple price protection. The price comes from the most recent quote; on a trade-only tape the trade price is the fallback.

### Step 4: End-of-Window MARKET Sweep

When the market clock (tick-time, not wall-clock) crosses `endTime` and the parent is still not fully placed, a single MARKET order sweeps the remainder so the parent leaves the strategy with the deadline kept.

### Worked Example

`p = 10%`, parent = 10,000, `minClipSize = 50`, `maxClipSize = 1,000`.

| Tick | Event | Size | cumVolume | target_executed | delta | clip emitted | already_emitted |
|------|-------|-----:|----------:|----------------:|------:|-------------:|----------------:|
| 1 | TRADE | 200 | 200 | 20 | 20 | — (below min) | 0 |
| 2 | TRADE | 400 | 600 | 60 | 60 | 60 | 60 |
| 3 | QUOTE | 0 | 600 | 60 | 0 | — | 60 |
| 4 | TRADE | 15,000 | 15,600 | 1,560 | 1,500 | 1,000 (max cap) | 1,060 |
| 5 | TRADE | 500 | 16,100 | 1,610 | 550 | 550 | 1,610 |

The cap on tick 4 leaves 500 still owed; the subsequent prints drain it without breaking the participation invariant.

## 4. POV in MariaAlpha

### Architecture

POV sits in the `strategy-engine` module and implements the same pluggable `TradingStrategy` interface as VWAP/TWAP/Momentum/IS, so it is auto-discovered as a Spring `@Component` and registered in the `StrategyRegistry` with **no wiring changes** anywhere else in the pipeline:

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

The `SymbolStrategyRouter` binds a symbol to a strategy name at runtime; the `StrategyEvaluationService` calls `onTick` / `evaluate` and applies the same ML signal confirmation/veto logic to POV signals as it does to the others. Metrics (`mariaalpha_strategy_signals_total`, `mariaalpha_strategy_evaluation_duration_ms`) are already tagged by `strategy`, so POV shows up in Prometheus/Grafana automatically under `strategy="POV"`.

### Parameters

| Parameter | Type | Default | Meaning |
|-----------|------|---------|---------|
| `targetQuantity` | int | 0 | Total parent quantity to execute |
| `side` | `BUY` \| `SELL` | `BUY` | Order side |
| `startTime` | `HH:mm[:ss]` (ET) | `09:30` | Window start (US Eastern) |
| `endTime` | `HH:mm[:ss]` (ET) | `16:00` | Window end (US Eastern) |
| `participationRate` | double (0.0 – 1.0) | 0.10 | Target fraction of cumulative traded volume |
| `minClipSize` | int | 1 | Minimum shares to actually emit; smaller computed deltas defer |
| `maxClipSize` | int | `Integer.MAX_VALUE` | Per-clip cap; protects against a single oversized block print pulling the strategy off course |

`getParameters()` additionally returns live `cumulativeMarketVolume` and `emittedQuantity` counters so an operator can see realised participation: `emittedQuantity / cumulativeMarketVolume` should hover near `participationRate` while the parent is mid-flight.

Configure at runtime via REST (parameters are addressed by **strategy name**, binding by **symbol**):

```bash
# Bind POV to TSLA
curl -X PUT -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
    -d '{"strategyName":"POV"}' \
    http://localhost:8080/api/strategies/TSLA

# Configure: 50,000 shares at 8% participation, 09:30-16:00 ET, min 200 / max 5,000 per clip
curl -X PUT -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
    -d '{"targetQuantity":50000,"side":"BUY","startTime":"09:30:00","endTime":"16:00:00","participationRate":0.08,"minClipSize":200,"maxClipSize":5000}' \
    http://localhost:8080/api/strategies/POV/parameters

# Inspect live progress (cumulativeMarketVolume / emittedQuantity)
curl -fsS -H "X-API-Key: local-dev-key" \
    http://localhost:8080/api/strategies/POV/parameters
```

### Market Time

Like the other slicing strategies, POV uses **tick timestamps** as its clock source (never `System.currentTimeMillis()`), converted to `America/New_York`. This makes the strategy fully deterministic and replayable: a unit test can simulate an entire trading session by feeding ticks with chosen timestamps and sizes, and historical replay behaves exactly as live trading would have.

### POV vs. the Schedule Strategies

| Property | VWAP | TWAP | IS | **POV** |
|----------|------|------|----|---------|
| Schedule source | Historical volume profile | Equal time slices | Equal time slices, front-loaded weights | **Reactive — live tape volume** |
| Commits up front | Yes | Yes | Yes | **No** |
| Per-clip type | LIMIT @ bid/ask | LIMIT @ bid/ask | LIMIT @ bid/ask | **LIMIT @ bid/ask** |
| End-of-window sweep | MARKET | MARKET | MARKET | **MARKET** |
| Completion guarantee within window | Yes (deterministic schedule) | Yes | Yes | **No — depends on tape** |
| Best when | Volume shape is stable | Even spacing suffices | Arrival-price benchmark, alpha decays | **Volume shape is uncertain** |

The four are kept as separate, self-contained classes (each in its own package) rather than sharing an abstract base, matching the existing repo convention that every `TradingStrategy` is independently discoverable and reads top-to-bottom without indirection.

### Relationship to Other Components

| Component | Role in POV |
|-----------|-------------|
| **Market Data Gateway** | Provides live TRADE ticks (the volume that drives participation) and QUOTE ticks (the bid/ask for limit-price resolution) |
| **ML Signal Service** | Can suppress a POV child order when a high-confidence signal contradicts the side |
| **Execution Engine** | Receives `OrderSignal`s, applies risk checks + SOR, submits to the exchange |
| **TCA (Post-Trade)** | Measures the achieved VWAP-benchmark and slippage; on quiet days POV's flat footprint typically scores well on both |

### Limitations of the Current Implementation

These mirror VWAP/TWAP/IS's MVP limitations and are deliberate scope cuts:

1. **No fill tracking / re-planning** — the strategy assumes each child fully fills; it does not reconcile against actual fills to revise `emittedQuantity` if a clip partially fills or rejects. Because the participation invariant is computed against intended (emitted) quantity, an under-fill silently widens the deficit until the next tick refreshes the delta.
2. **No latency / look-ahead protection** — every TRADE tick contributes its size as soon as it arrives. A burst of late-arriving prints will be treated as "current" volume; in production POV typically uses a short trailing window (e.g. last N seconds) and a per-second cap to throttle clips during bursts.
3. **No randomisation** — clip timing/size is deterministic, so on a printer-heavy tape a counterparty could reverse-engineer the participation rate. Production POVs randomise both.
4. **No interval-cadence floor** — the strategy never trades while the tape is silent. A production POV would optionally trade a small minimum slice every N seconds even if `delta` is below `minClipSize`, to avoid going dark on illiquid names; here the end-of-window MARKET sweep is the only catch-up mechanism.
5. **`size = 0` quotes do not contribute volume** — by design (see Step 1), but worth noting for replay against datasets that encode aggregated volume as size-0 ticks.

## 5. Testing

- **Unit** — `PovStrategyTest` covers the participation invariant (clip = `p × volume − already_emitted`), the `minClipSize` defer / `maxClipSize` cap behaviour, parent-target cap, QUOTE-doesn't-count-as-volume, side-aware bid/ask price resolution, trade-price fallback, the end-of-window MARKET sweep (including "nothing to sweep" and "sweep only once"), zero participation, parameter round-trip + state reset, partial parameter updates, and a 100-tick session simulation that confirms cumulative emitted volume tracks `p × cumulativeMarketVolume` within the parent cap.
- **Integration** — `TickConsumerIntegrationTest` publishes a 5,000-share TRADE tick to `market-data.ticks` over a Kafka Testcontainer and asserts a 500-share POV `OrderSignal` (`p = 0.10`) lands on `strategy.signals` with `strategy = "POV"`.
- **End-to-end** — `SimulatedHappyPathE2ETest` binds `TSLA → POV` over the full Docker Compose stack and asserts the resulting position fills and the persisted order carries `strategy = "POV"`.

## 6. Further Reading

- **Kissell & Glantz (2003)**: "Optimal Trading Strategies" — comprehensive coverage including a chapter on volume-participation algorithms.
- **Johnson (2010)**: "Algorithmic Trading & DMA" — practical guide that walks through POV alongside VWAP / TWAP / IS, including the reactive-vs-scheduled trade-off.
- **Almgren & Chriss (2000)**: "Optimal Execution of Portfolio Transactions" — the framework whose constant-impact-rate idea POV implements as a discrete on-tape heuristic.
- [`vwap-strategy-explainer.md`](vwap-strategy-explainer.md) — the schedule-driven sibling that assumes a known volume profile.
- [`twap-strategy-explainer.md`](twap-strategy-explainer.md) — the schedule-driven sibling that ignores volume entirely.
- [`implementation-shortfall-strategy-explainer.md`](implementation-shortfall-strategy-explainer.md) — the front-loaded schedule strategy at the opposite end of the patient/aggressive spectrum.
- [`tca-methodology.md`](tca-methodology.md) — how the achieved participation, slippage, and VWAP-benchmark are measured post-trade.
