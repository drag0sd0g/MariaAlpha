# Momentum / Trend-Following Strategy: Concepts and MariaAlpha Implementation

## 1. What Is a Momentum Strategy?

**Momentum** (or **trend-following**) trading rests on a single empirical observation: prices that have been rising tend to keep rising, and prices that have been falling tend to keep falling, over short-to-medium horizons. Rather than predicting a fair value and trading toward it (mean reversion), a momentum strategy *joins* an established trend and rides it until the trend shows signs of breaking.

This is fundamentally different from the VWAP and TWAP strategies already in MariaAlpha:

| | VWAP / TWAP | Momentum |
|---|---|---|
| **Purpose** | *Execution* — work a known parent order into the market with minimal impact | *Alpha* — decide **whether and when** to be in a position at all |
| **Input** | A fixed `targetQuantity` to buy or sell | Live price action; quantity per trade is a fixed clip |
| **Output cadence** | A scheduled child order per time slice/bin | An entry when a trend confirms, an exit when it reverses |
| **"Done" condition** | Parent quantity executed or window closed | Never — it trades in and out as trends come and go |

In short: VWAP/TWAP answer *"how do I execute this order?"*; Momentum answers *"should I have a position right now?"*.

## 2. The Three Confirmation Signals

MariaAlpha's Momentum strategy combines three classic technical signals so that no single noisy indicator can trigger a trade on its own.

### 2.1 EMA Crossover (the trend trigger)

An **Exponential Moving Average (EMA)** is a moving average that weights recent prices more heavily, with smoothing factor `alpha = 2 / (period + 1)`. Two EMAs of different lengths are tracked:

- A **fast EMA** (default 20 periods) — reacts quickly to price changes.
- A **slow EMA** (default 50 periods) — reacts slowly, representing the underlying trend.

The trigger is the **crossover**:

- **Bullish crossover** — the fast EMA crosses *above* the slow EMA → recent prices are pulling up faster than the trend → an uptrend is starting.
- **Bearish crossover** — the fast EMA crosses *below* the slow EMA → a downtrend is starting.

A crossover is a one-shot *event*: it fires on the single tick where the ordering flips, not on every tick the fast EMA happens to be above the slow EMA.

### 2.2 RSI (the exhaustion filter)

The **Relative Strength Index (RSI)** is a 0–100 oscillator measuring the magnitude of recent gains versus losses (Wilder's smoothing, default 14 periods). It guards against buying a move that has already run too far:

- RSI **≥ `rsiOverbought`** (default 70) → the move is overextended. Don't *enter* long here; if already long, *exit*.
- RSI **≤ `rsiOversold`** (default 30) → oversold. Don't *enter* short here; if already short, *exit*.

MariaAlpha's RSI is computed identically to the ML Signal Service's `indicators.rsi` (Wilder's method, seeded with a simple average over the first `rsiPeriod` deltas, neutral 50 until enough data exists), so the two services agree on what "overbought" means.

### 2.3 Volume Confirmation (the conviction filter)

A breakout on thin volume is suspect — it can reverse as easily as it appeared. The strategy requires the triggering trade's size to exceed `volumeMultiplier ×` (default 1.5×) the average size of the most recent `volumeLookback` trades (default 20). A real trend usually arrives with a surge of participation, so volume confirmation filters out low-conviction noise.

## 3. The Entry / Exit State Machine

The strategy tracks its own notion of position — `FLAT`, `LONG`, or `SHORT` — based on the signals it has emitted (it assumes each child order fills). The `side` parameter selects the entry direction.

### Long case (`side = BUY`)

```
            bullish crossover
            AND rsi < rsiOverbought          bearish crossover
            AND volume confirmed             OR rsi >= rsiOverbought
                                             OR stop-loss hit
   ┌──────┐ ───────────────────────► ┌──────┐ ───────────────────────► ┌──────┐
   │ FLAT │                          │ LONG │                          │ FLAT │
   └──────┘   BUY  tradeQuantity     └──────┘   SELL tradeQuantity      └──────┘
              LIMIT @ ask                       MARKET (flatten)
```

### Short case (`side = SELL`)

The mirror image: **enter short** on a bearish crossover with RSI not oversold and volume confirmed (`SELL` LIMIT at the bid); **exit** on a bullish crossover, RSI oversold, or stop-loss (`BUY` MARKET to flatten).

### Order types

- **Entries are `LIMIT`** orders at the current best ask (buys) or best bid (sells), giving price protection on the way in.
- **Exits are `MARKET`** orders. An exit means the thesis is invalidated (trend reversed, momentum exhausted, or the stop tripped), so completing the flatten matters more than shaving a few cents — the same philosophy as the VWAP/TWAP end-of-window sweep.

### Stop-loss

While in a position, if the mark (latest trade price, or quote mid between trades) moves `stopLossPct`% against the entry price, the position is flattened immediately. Setting `stopLossPct = 0` disables the stop.

## 4. Momentum in MariaAlpha

### Architecture

Momentum sits in the `strategy-engine` module and implements the same pluggable `TradingStrategy` interface as VWAP and TWAP, so it is auto-discovered as a Spring `@Component` and registered in the `StrategyRegistry` with **no wiring changes** anywhere else in the pipeline:

```
                  market-data.ticks (Kafka)
                         |
                         v
             +------- Strategy Engine -------+
             |                               |
             |  TradingStrategy interface    |
             |    +-- VwapStrategy           |
             |    +-- TwapStrategy           |
             |    +-- MomentumStrategy       |
             |                               |
             |  StrategyRegistry             |
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

The `SymbolStrategyRouter` binds a symbol to a strategy name at runtime; the `StrategyEvaluationService` calls `onTick` / `evaluate` and applies the same ML signal confirmation/veto logic to Momentum signals as it does to VWAP and TWAP. Metrics (`mariaalpha_strategy_signals_total`, `mariaalpha_strategy_evaluation_duration_ms`) are already tagged by `strategy`, so Momentum shows up in Prometheus/Grafana automatically under `strategy="MOMENTUM"`.

### Parameters

| Parameter | Type | Default | Meaning |
|-----------|------|---------|---------|
| `fastPeriod` | int | 20 | Fast EMA period |
| `slowPeriod` | int | 50 | Slow EMA period |
| `rsiPeriod` | int | 14 | RSI period |
| `rsiOverbought` | double | 70 | RSI level above which longs exit / are blocked |
| `rsiOversold` | double | 30 | RSI level below which shorts exit / are blocked |
| `volumeMultiplier` | double | 1.5 | Required ratio of the trigger trade's size to the recent average (`0` disables) |
| `volumeLookback` | int | 20 | Number of recent trades in the volume average |
| `tradeQuantity` | int | 100 | Shares per entry (the fixed clip) |
| `side` | `BUY` \| `SELL` | `BUY` | Entry direction — `BUY` = long-only, `SELL` = short-only |
| `stopLossPct` | double | 2.0 | Stop-loss distance from entry, in percent (`0` disables) |
| `warmupTrades` | int | 0 (= `slowPeriod`) | Trades to observe before acting on a crossover |

`getParameters()` additionally returns live `position`, `tradesObserved`, `fastEma`, `slowEma`, and `rsi` for monitoring.

Configure at runtime via REST (parameters are addressed by **strategy name**, binding by **symbol**):

```bash
# Bind Momentum to NVDA
curl -X PUT -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
    -d '{"strategyName":"MOMENTUM"}' \
    http://localhost:8080/api/strategies/NVDA

# Configure: long-only 20/50 EMA cross, 100-share clips, 2% stop
curl -X PUT -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
    -d '{
          "fastPeriod": 20,
          "slowPeriod": 50,
          "rsiPeriod": 14,
          "rsiOverbought": 70,
          "rsiOversold": 30,
          "volumeMultiplier": 1.5,
          "tradeQuantity": 100,
          "side": "BUY",
          "stopLossPct": 2.0
        }' \
    http://localhost:8080/api/strategies/MOMENTUM/parameters
```

### Market Time and Determinism

Like VWAP and TWAP, Momentum is driven entirely by the **tick stream**, never the wall clock. Indicators advance only on `TRADE` ticks (the canonical "last price" tape); `QUOTE` ticks merely refresh the best bid/ask used to price child orders and the mark used for the stop-loss. This makes the strategy fully deterministic and replayable: a unit test can feed a chosen price sequence and assert the exact entry/exit, and historical replay behaves exactly as live trading would have.

### Relationship to Other Components

| Component | Role in Momentum |
|-----------|------------------|
| **Market Data Gateway** | Provides the trade/quote ticks that drive the EMAs, RSI, volume, and stop mark |
| **ML Signal Service** | Can suppress a Momentum entry when a high-confidence ML signal contradicts the side; the Phase 2 regime classifier (issue 2.3.1) is the intended source for routing Momentum to *trending* regimes (FR-17) |
| **Execution Engine** | Receives `OrderSignal`s, applies risk checks + SOR, submits to the exchange |
| **TCA (Post-Trade)** | Measures achieved fill price against benchmarks for each completed order |

### Limitations of the Current Implementation

These are deliberate scope cuts for the MVP, in the same spirit as the VWAP/TWAP limitations:

1. **Single-symbol per instance** — like VWAP and TWAP, one strategy instance carries one symbol's worth of rolling state, so a Momentum instance should be bound to a single symbol at a time. Per-symbol state is a future enhancement.
2. **Assumed fills** — the strategy tracks its position from the signals it emits; it does not yet reconcile against actual fills, so a rejected or partially-filled entry can leave its internal `position` out of step with reality.
3. **Fixed clip size** — every entry trades exactly `tradeQuantity`; there is no volatility- or conviction-scaled sizing and no pyramiding into a strengthening trend.
4. **EMA seeding** — both EMAs seed from the first observed price and converge over `warmupTrades`; very early crossovers on a cold start are therefore less reliable, which is what the warmup guard exists to manage.
5. **No re-plan on regime change** — regime-aware algorithm selection (Momentum for TRENDING, VWAP/TWAP for MEAN_REVERTING/LOW_VOLATILITY) is specified in FR-17 but depends on the regime classifier (issue 2.3.1) and is out of scope here.

## 5. Testing

- **Unit** — `MomentumStrategyTest` covers the bullish-crossover long entry, RSI-overbought and volume suppression of entries, no re-entry while in position, all three long exits (reverse crossover, RSI overbought, stop-loss via an adverse quote), the short-side mirror (entry on bearish crossover, exit on bullish), trade-price fallback when no quote is present, quote ticks not driving indicators, symbol filtering, parameter round-trip + state reset, and partial parameter updates.
- **Integration** — `TickConsumerIntegrationTest` publishes a two-trade uptrend to `market-data.ticks` over a Kafka Testcontainer and asserts a `MOMENTUM` BUY `OrderSignal` lands on `strategy.signals`.
- **End-to-end** — `SimulatedHappyPathE2ETest` binds `GOOGL → MOMENTUM` over the full Docker Compose stack and asserts the simulated uptrend produces a persisted, `FILLED` `MOMENTUM` BUY order without tripping the daily loss limit.

## 6. Further Reading

- **Jegadeesh & Titman (1993)**: "Returns to Buying Winners and Selling Losers" — the foundational academic evidence for cross-sectional momentum.
- **Moskowitz, Ooi & Pedersen (2012)**: "Time Series Momentum" — trend-following across asset classes.
- **Kaufman (2013)**: "Trading Systems and Methods" — practical coverage of moving-average crossover and RSI systems.
- [`vwap-strategy-explainer.md`](vwap-strategy-explainer.md), [`twap-strategy-explainer.md`](twap-strategy-explainer.md), and [`implementation-shortfall-strategy-explainer.md`](implementation-shortfall-strategy-explainer.md) — the sibling *execution* strategies this one complements.
