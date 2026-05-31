# Transaction Cost Analysis (TCA) Methodology

## 1. Introduction

Transaction Cost Analysis (TCA) is the automated process of measuring how much it actually cost to execute a trade, compared to what it would have cost if the order had filled instantly at the moment the trading decision was made.

Every time an order reaches `FILLED` status, the `post-trade` service computes four metrics — all expressed in **basis points (bps)** — and stores them in `tca_results`. The results are also published to Kafka so downstream analytics consumers can aggregate them.

> **What is a basis point?** One basis point = 0.01%. So 5 bps = 0.05% = $5 of cost per $10,000 of notional traded. Basis points are the standard unit in finance for expressing small price differences because they avoid the awkwardness of writing "0.05%".

**Sign convention used throughout this document:**
- A **positive** value = a **cost** to the trader (we paid more than expected, or received less).
- A **negative** value = a **benefit** (price improvement — we paid less, or received more).

---

## 2. System Architecture & Data Flow

### 2.1 End-to-End Lifecycle

```
order-manager          execution-engine        post-trade
─────────────────      ─────────────────       ─────────────────────────────────────────
1. Order created  ──► 2. Order routed to   ──► 3. On FIRST lifecycle event:
   (createdAt         exchange; fills          capture market snapshot
    recorded)         streamed to Kafka        (bid, ask, mid) into arrival_snapshots

                                           ──► 4. On FILLED event:
                                               a. Fetch order details + fills via REST
                                               b. Look up arrival snapshot
                                               c. Compute interval VWAP from tick cache
                                               d. Run TcaCalculator → 4 bps metrics
                                               e. Persist to tca_results
                                               f. Publish to analytics.tca (Kafka)
```

### 2.2 Data Sources for Each Metric Component

| Component | Source | How it gets there |
| :--- | :--- | :--- |
| Order metadata (symbol, side, strategy, createdAt) | `order-manager` REST `GET /api/orders/{id}` | Fetched once at TCA computation time |
| Fill details (fill prices, quantities, commissions, timestamps) | `order-manager` REST (nested in order response) | Same REST call, `fills[]` array |
| Arrival quote (bid, ask, mid at order creation time) | `post-trade` in-memory cache + `arrival_snapshots` table | Captured from `market-data.ticks` Kafka topic; persisted on first lifecycle event |
| VWAP tick data (trade prices and volumes during execution) | `post-trade` in-memory cache | Streamed from `market-data.ticks`; only `EventType.TRADE` ticks are used |

### 2.3 Arrival Price: How It Is Captured

The **arrival price** is the market mid-price at the exact moment the trading decision was made — i.e. when the order was created. This is the benchmark for slippage and implementation shortfall.

Capturing it requires looking at the market tick cache for the **nearest tick at or before `order.createdAt`** for that symbol, within a 60-second lookback window. If no tick is found within 60 seconds, the arrival snapshot is not stored and arrival-dependent metrics (`slippage`, `IS`) will be `null` for that order.

The mid-price is derived as:
- If both `bid` and `ask` are available and positive: `mid = (bid + ask) / 2`
- Otherwise: `mid = tick.price` (last trade price as fallback)

This snapshot is written to `arrival_snapshots` on the **first** lifecycle event seen for an order (not just on FILLED), so it is captured before execution completes. It is idempotent — repeated events for the same order do not overwrite the row.

---

## 3. Quick Reference: All Four Formulas

| Metric | Formula | Inputs |
| :--- | :--- | :--- |
| **Slippage** | `signedCost(side, avgFill, arrival) / arrival × 10,000` | avgFill, arrivalMid |
| **Impl. Shortfall** | `(signedCost(side, avgFill, arrival) × qty + commission) / (arrival × qty) × 10,000` | avgFill, arrivalMid, qty, totalCommission |
| **VWAP Benchmark** | `signedCost(side, avgFill, vwap) / vwap × 10,000` | avgFill, intervalVwap |
| **Spread Cost** | `(ask − bid) / mid / 2 × 10,000` | bid, ask, mid at arrival |

Where `signedCost(side, avgFill, benchmark)` is defined as:
- BUY: `avgFill − benchmark` (positive when we paid more than benchmark → cost)
- SELL: `benchmark − avgFill` (positive when we received less than benchmark → cost)

---

## 4. Metric Definitions

### 4.1 Slippage (Arrival Price Slippage)

**What it measures:** How much worse (or better) our average fill price was compared to the market price at the moment we decided to trade. This is the most basic measure of execution quality.

**Formula:**

```
signedCost = avgFill − arrivalMid          (BUY)
           = arrivalMid − avgFill          (SELL)

Slippage (bps) = signedCost / arrivalMid × 10,000
```

**Intuition:**
- BUY order: if we paid $100.05 but the market was at $100.00 when we decided to buy, we paid 5 extra cents per share. That is `5/100 × 10,000 = 5 bps` of slippage.
- SELL order: if we received $99.95 but the market was at $100.00, we received 5 cents less per share. Same formula, same 5 bps of cost.

**Returns `null` if:** `arrivalMid` is missing or zero (no arrival snapshot within the 60-second lookback window).

---

### 4.2 Implementation Shortfall (IS)

**What it measures:** The total real-world cost of the trade, including both price slippage *and* explicit commissions, expressed as a fraction of the order's notional value. It answers: "How much did the decision to trade cost us, in total?"

**Formula:**

```
priceCost($) = signedCost(side, avgFill, arrivalMid) × filledQty
totalCost($) = priceCost + totalCommission

IS (bps) = totalCost($) / (arrivalMid × filledQty) × 10,000
```

where `totalCommission` is the sum of all per-fill commission values from order-manager. If commissions are missing they are treated as zero, in which case `IS = Slippage`.

**Intuition with numbers:**

> BUY 1,000 shares, arrival = $100.00, avgFill = $100.10, commission = $20
>
> priceCost = (100.10 − 100.00) × 1,000 = $100
> totalCost = $100 + $20 = $120
> notional = 100.00 × 1,000 = $100,000
> IS = 120 / 100,000 × 10,000 = **12 bps**

Note that if the fill price had equalled the arrival price (perfect execution), IS would be `20 / 100,000 × 10,000 = 2 bps` — the commission alone.

**Returns `null` if:** `arrivalMid` or `filledQty` is missing or zero.

---

### 4.3 VWAP Benchmark

**What it measures:** How our average fill price compared to the Volume-Weighted Average Price (VWAP) of the market during our execution window. This is the standard benchmark for evaluating VWAP and TWAP algorithms.

**Formula:**

```
marketVwap = Σ(tradePrice_i × tradeSize_i) / Σ(tradeSize_i)
             over [order.createdAt, lastFill.filledAt]
             using EventType.TRADE ticks only

VWAP_diff (bps) = signedCost(side, avgFill, marketVwap) / marketVwap × 10,000
```

**Execution window:**
- **Start:** `order.createdAt` — when the order entered the system.
- **End:** timestamp of the **last fill** for the order.

Only `EventType.TRADE` ticks are included. Quote-only ticks (bid/ask updates with no transaction) are excluded because they carry no volume and would distort the VWAP.

**Intuition:** If our VWAP strategy was supposed to match the market's volume-weighted average, a positive VWAP benchmark means we underperformed — we paid more (buy) or received less (sell) than the market average during our window.

**Returns `null` if:** no TRADE ticks exist in the execution window in the cache (the tick cache has a 6-hour TTL).

---

### 4.4 Spread Cost

**What it measures:** The implicit cost of market structure — specifically, the cost of "crossing the spread" at the moment of the trading decision. This is not caused by our own execution; it is an unavoidable cost in any traded market.

**Formula:**

```
SpreadCost (bps) = (ask − bid) / mid / 2 × 10,000
```

The `/2` factor reflects the convention that a liquidity-taking order crosses *half* the spread: a buyer pays the ask (mid + half-spread) and a seller receives the bid (mid − half-spread). Dividing by `mid` normalises to a percentage.

**Intuition:**

> bid = $99.98, ask = $100.02, mid = $100.00
> spread = $0.04
> SpreadCost = 0.04 / 100.00 / 2 × 10,000 = **2 bps**

This cost is the same whether you buy or sell. High spread cost indicates an illiquid market; it is not the algorithm's fault. Comparing spread cost to slippage helps separate "market structure cost" from "execution quality cost".

**Clamped to zero if:** `ask < bid` (crossed book — treated as free liquidity).  
**Returns `null` if:** bid or ask is missing at the arrival snapshot.

---

## 5. Worked Example

**Scenario:** BUY 1,000 AAPL shares.

| Input | Value |
| :--- | :--- |
| Arrival mid-price | $180.00 |
| Arrival bid | $179.98 |
| Arrival ask | $180.02 |
| Average fill price | $180.05 |
| Filled quantity | 1,000 shares |
| Total commission | $5.00 |
| Interval VWAP (market) | $180.03 |

**Calculations:**

```
Slippage  = (180.05 − 180.00) / 180.00 × 10,000
          = 0.05 / 180.00 × 10,000
          = 2.7778 bps

IS        = ((180.05 − 180.00) × 1,000 + 5.00) / (180.00 × 1,000) × 10,000
          = (50.00 + 5.00) / 180,000 × 10,000
          = 55.00 / 180,000 × 10,000
          = 3.0556 bps

VWAP diff = (180.05 − 180.03) / 180.03 × 10,000
          = 0.02 / 180.03 × 10,000
          = 1.1109 bps

Spread    = (180.02 − 179.98) / 180.00 / 2 × 10,000
          = 0.04 / 180.00 / 2 × 10,000
          = 1.1111 bps
```

**Reading the result:** We paid 2.78 bps above the arrival price. After adding the $5 commission, total IS is 3.06 bps. We performed 1.11 bps worse than the market VWAP — meaning the algorithm was slightly slow relative to the market's volume-weighted average. The spread of 1.11 bps is the unavoidable market-structure cost regardless of execution quality.

---

## 6. Edge Cases & Fallback Logic

| Scenario | What happens |
| :--- | :--- |
| **No tick within 60s of order creation** | No arrival snapshot is stored. The entire TCA computation is skipped — all four metrics are absent for this order. |
| **No TRADE ticks in execution window** | `vwapBenchmarkBps` is `null`; the other three metrics are still computed normally (if arrival data is available). |
| **Missing commission data** | `totalCommission` is treated as `0`. IS effectively equals Slippage. |
| **Crossed book (ask < bid)** | `spreadCostBps` is computed as `0` — negative spreads are not possible in practice; the crossed state is transient. |
| **Order cancelled or rejected** | TCA is not computed. Only `FILLED` orders trigger computation. |
| **Duplicate FILLED events (Kafka replay)** | Idempotent: `existsByOrderId` check prevents a second TCA row from being written. The first result is returned. |

---

## 7. Interpreting Results

| Metric | Negative value | Zero | Positive value |
| :--- | :--- | :--- | :--- |
| **Slippage** | Price improvement — better fill than arrival | Perfect match to arrival | Cost — paid more (buy) or received less (sell) |
| **Impl. Shortfall** | Net gain vs. decision price (alpha generated) | Break-even | Total cost incurred |
| **VWAP Benchmark** | Outperformed market average | Matched market average | Underperformed market average |
| **Spread Cost** | N/A — always ≥ 0 | N/A — always ≥ 0 | Market structure cost; higher in illiquid markets |

**Aggregating across orders:** Always use **notional-weighted averages** (weight each order's bps by its `arrivalMid × filledQty`) rather than a simple arithmetic mean. A 10-share order and a 10,000-share order should not contribute equally to the portfolio average.

---

## 8. Numerical Precision

All calculations use `MathContext(20, HALF_UP)` throughout. Output values are rounded to **4 decimal places** (`setScale(4, HALF_UP)`) before being stored. This gives sub-0.0001 bps precision — more than sufficient for practical use.

---

## 9. Future Enhancements (Phase 2)

1. **Opportunity Cost:** P&L impact for partially filled orders — the value of shares that did not execute, measured against the close price.
2. **Time Cost:** Interest charge on capital tied up for the duration of execution.
3. **Ex-Ante TCA:** Pre-trade slippage estimates based on historical volatility and market impact models, computed before the order is placed.
4. **Regime Segmentation:** Grouping TCA results by market regime (high volatility, low liquidity) to identify strategy fragility under different conditions.
