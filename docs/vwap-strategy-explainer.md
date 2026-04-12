# VWAP Strategy: Concepts and MariaAlpha Implementation

## 1. What Is VWAP?

**VWAP** (Volume-Weighted Average Price) is the benchmark price calculated by dividing the total dollar value traded by the total volume traded over a given period (typically one trading day):

```
VWAP = SUM(Price_i * Volume_i) / SUM(Volume_i)
```

Where each `i` represents a trade or time bar during the period.

For example, if during the first hour of trading:
- 10,000 shares traded at $100.00
- 5,000 shares traded at $101.00
- 15,000 shares traded at $99.50

Then VWAP = (10000 * 100 + 5000 * 101 + 15000 * 99.50) / (10000 + 5000 + 15000) = $99.83

VWAP serves two distinct purposes in trading:

1. **As a benchmark**: Portfolio managers compare their execution price against VWAP to measure execution quality. Buying below VWAP (or selling above it) is considered "good" execution.

2. **As an execution algorithm**: A VWAP algorithm attempts to execute a large order such that the average fill price matches the day's VWAP. This is what MariaAlpha implements.

## 2. Why Use VWAP Execution?

Large institutional orders (e.g., "buy 500,000 shares of AAPL") cannot be submitted as a single order without severely impacting the market price. A 500,000-share market order would exhaust available liquidity at the best price and "walk up" the order book, resulting in a much higher average fill price.

VWAP execution solves this by **slicing** the parent order into many smaller child orders spread across the trading day, proportional to the expected volume pattern. This achieves several goals:

- **Minimizes market impact**: Small orders don't move the price
- **Tracks the benchmark**: By trading proportionally to volume, the average fill price naturally approaches the day's VWAP
- **Reduces information leakage**: The trading pattern blends in with normal market activity
- **Provides predictability**: The client knows approximately when and how much will be traded

## 3. The Volume Profile

The key input to a VWAP algorithm is the **volume profile** -- a prediction of how trading volume will be distributed across the day. US equity markets exhibit a characteristic "U-shape" (sometimes called a "smile"):

```
Volume
  |
  |**                                               **
  | **                                            **
  |  **                                         **
  |   ***                                     **
  |     ****                               ***
  |        *****                       ****
  |            ********         *******
  |                   ***********
  |
  +----------------------------------------------------> Time
  9:30    10:30   11:30   12:30   1:30    2:30    3:30  4:00
```

- **Opening (9:30-10:30)**: High volume as overnight orders execute and market participants react to news
- **Midday (11:00-14:00)**: Lower volume as the initial rush subsides
- **Close (15:00-16:00)**: High volume as institutional traders complete daily targets, index rebalances execute, and closing auctions approach

The volume profile is typically derived from **historical data** -- averaging the intraday volume distribution over the past N days (e.g., 20 days). In MariaAlpha, this could be computed from `HistoricalBar` data (1-minute or 5-minute bars), though the initial implementation accepts the profile as a parameter.

## 4. The VWAP Slicing Algorithm

Given:
- **Parent order**: BUY 100,000 shares of AAPL
- **Trading window**: 09:30 to 16:00 ET
- **Volume profile**: 13 thirty-minute bins with volume fractions

The algorithm works as follows:

### Step 1: Allocate Shares to Time Bins

Each time bin receives a share allocation proportional to its expected volume fraction:

| Bin | Time | Volume Fraction | Allocation (of 100,000) |
|-----|------|-----------------|------------------------|
| 0 | 09:30-10:00 | 12% | 12,000 |
| 1 | 10:00-10:30 | 9% | 9,000 |
| 2 | 10:30-11:00 | 7% | 7,000 |
| 3 | 11:00-11:30 | 6% | 6,000 |
| 4 | 11:30-12:00 | 5% | 5,000 |
| 5 | 12:00-12:30 | 5% | 5,000 |
| 6 | 12:30-13:00 | 5% | 5,000 |
| 7 | 13:00-13:30 | 5% | 5,000 |
| 8 | 13:30-14:00 | 6% | 6,000 |
| 9 | 14:00-14:30 | 7% | 7,000 |
| 10 | 14:30-15:00 | 9% | 9,000 |
| 11 | 15:00-15:30 | 11% | 11,000 |
| 12 | 15:30-16:00 | 13% | 13,000 |
| | | **100%** | **100,000** |

### Step 2: Execute Each Bin

As the trading day progresses:
1. When the market clock enters a new time bin, the algorithm emits a **child order** for that bin's allocated quantity
2. The child order is typically a **LIMIT order** at the current best ask (for buys) or best bid (for sells), providing price protection
3. If the limit order doesn't fill immediately, the algorithm can adjust (cancel/replace at a new price) -- though this refinement is for later iterations

### Step 3: End-of-Day Sweep

If at the end of the trading window there are still unexecuted shares remaining (from bins that were missed or partially filled), the algorithm emits a final **MARKET order** to sweep the remainder. This ensures the parent order is fully executed by the deadline, even at a slightly worse price.

### Rounding

When the target quantity doesn't divide evenly by the volume fractions, rounding errors accumulate. The standard approach is to:
1. Round each bin's allocation to the nearest integer
2. Assign any remaining shares (positive or negative difference) to the last bin

For example, with target = 1,001 shares and three bins at 33.3% each:
- Bin 0: round(1001 * 0.333) = 333
- Bin 1: round(1001 * 0.333) = 333
- Bin 2: 1001 - 333 - 333 = 335 (absorbs remainder)

## 5. VWAP in MariaAlpha

### Architecture

MariaAlpha's VWAP strategy sits in the `strategy-engine` module and follows the pluggable `TradingStrategy` interface:

```
                  market-data.ticks (Kafka)
                         |
                         v
             +------- Strategy Engine -------+
             |                               |
             |  TradingStrategy interface     |
             |    +-- VwapStrategy            |
             |    +-- TwapStrategy (future)   |
             |    +-- MomentumStrategy (fut.) |
             |                               |
             |  StrategyRegistry              |
             |    auto-discovers @Component   |
             |    strategies via Spring DI    |
             |                               |
             +-------------------------------+
                         |
                         v
                    OrderSignal
                         |
                         v
                  Execution Engine
```

### Data Flow

1. **Tick ingestion** (1.3.3, future): Kafka consumer deserializes `MarketTick` JSON from `market-data.ticks` topic
2. **Tick routing**: Each tick is passed to the active strategy for that symbol via `strategy.onTick(tick)`
3. **Evaluation**: The strategy engine calls `strategy.evaluate(symbol)` after each tick
4. **Signal emission**: If the VWAP algorithm determines it's time to trade (new time bin entered), it returns an `OrderSignal`
5. **Execution**: The order signal is passed to the Execution Engine for risk checks and routing

### Market Time

The VWAP strategy uses **tick timestamps** as its clock source, not the system wall clock. This is critical for:
- **Testability**: Unit tests can simulate a full trading day by providing ticks with specific timestamps
- **Replay correctness**: When replaying historical data, the strategy behaves as if it were trading live at that time
- **Clock independence**: No reliance on `System.currentTimeMillis()` or `Clock.systemDefaultZone()`

All times are converted to `America/New_York` (US Eastern Time) since the primary venue is US equities.

### Relationship to Other Components

| Component | Role in VWAP |
|-----------|-------------|
| **Market Data Gateway** | Provides live ticks (price, volume, bid/ask) that drive the strategy |
| **HistoricalBar data** | Source for computing volume profiles (future enhancement) |
| **ML Signal Service** | Can suppress or confirm VWAP signals based on directional confidence (1.3.4) |
| **Execution Engine** | Receives OrderSignals, applies risk checks, submits to exchange |
| **TCA (Post-Trade)** | Measures how well the achieved fill price tracked VWAP benchmark |

### Limitations of the MVP Implementation

1. **Static volume profile**: The profile is provided as a parameter, not computed dynamically from historical data. A future enhancement would auto-compute it from `HistoricalBar` data.

2. **One signal per bin**: The current implementation emits a single child order per time bin. A production VWAP would further subdivide bins into smaller "wavelets" for smoother execution.

3. **No fill tracking**: The strategy doesn't yet receive fill confirmations. It assumes each child order will fill completely. When the Execution Engine integration is complete (1.5.x), the strategy can track actual fills and adjust remaining quantities.

4. **No adaptive behavior**: Production VWAP algorithms adapt in real-time -- if actual volume is running ahead of the profile, they accelerate; if behind, they slow down. This is a Phase 2 enhancement.

5. **No participation rate cap**: A production VWAP limits each child order to a percentage of actual volume (e.g., max 20% of the bar's volume) to avoid dominating the tape. This is a future enhancement.

## 6. VWAP vs. TWAP

| Aspect | VWAP | TWAP |
|--------|------|------|
| Distribution | Proportional to volume | Equal across time |
| Best for | Liquid stocks with predictable volume patterns | Illiquid stocks, overnight sessions, or when volume profile is unknown |
| Complexity | Higher (needs volume profile) | Lower (just divide by time slices) |
| Benchmark tracking | Better for volume-weighted benchmark | Better for time-weighted benchmark |
| Market impact | Lower (trades with the crowd) | Higher (may trade against thin volume) |

MariaAlpha implements TWAP as a separate strategy (1.3.x future issue) which simply divides the target quantity into equal slices across evenly-spaced intervals.

## 7. Further Reading

- **Almgren & Chriss (2000)**: "Optimal Execution of Portfolio Transactions" -- foundational paper on optimal trade execution
- **Kissell & Glantz (2003)**: "Optimal Trading Strategies" -- comprehensive coverage of VWAP, TWAP, and implementation shortfall algorithms
- **Johnson (2010)**: "Algorithmic Trading & DMA" -- practical guide to execution algorithms including VWAP
