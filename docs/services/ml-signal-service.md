# ML Signal Service — How It Works

## Overview

The ML Signal Service is a Python microservice that sits between the market data stream and the strategy engine. Its job is to consume real-time market data ticks from Kafka, compute technical indicator features from those ticks, and serve directional predictions (LONG / SHORT / NEUTRAL) with a confidence score via gRPC. The strategy engine calls the ML service before acting on each order signal — if the ML model has high confidence that the signal is wrong, the signal is suppressed.

The service has two interfaces:

| Interface | Port | Protocol | Purpose |
|-----------|------|----------|---------|
| gRPC server | 50051 | HTTP/2 + Protocol Buffers | `GetSignal`, `GetRegime`, `StreamSignals` — called by the strategy engine |
| FastAPI sidecar | 8090 | HTTP/1.1 + JSON | `/health`, `/ready`, `/metrics`, `/v1/models/reload` — ops and observability |

The ML service is **advisory, not authoritative**. If the service is down, the strategy engine proceeds without ML input — trading strategies are the primary decision-makers.

---

## Architecture

```
Kafka (market-data.ticks)
       │
       ▼
 TickConsumer (background thread)
       │  deserialises JSON → extracts price + volume
       ▼
 FeatureEngine
       │  aggregates ticks into 1-minute OHLCV bars
       │  computes 15 technical indicator features per symbol
       │  notifies StreamSignals listeners
       ▼
 SignalModel (LightGBM)
       │  takes 15 features → predicts direction + confidence
       │
       ├──► gRPC GetSignal (strategy engine calls this)
       ├──► gRPC StreamSignals (push-based delivery)
       └──► FastAPI /metrics (Prometheus scrape)
```

The service runs three concurrent components:

1. **Kafka consumer thread** — polls `market-data.ticks`, feeds ticks to the feature engine.
2. **gRPC server thread pool** — serves `GetSignal`, `GetRegime`, and `StreamSignals` RPCs.
3. **FastAPI/uvicorn main thread** — serves health checks, metrics, and the model reload endpoint.

---

## Technical Indicators Explained

The ML model requires 15 input features derived from raw price and volume data. These features are computed from a rolling window of 1-minute OHLCV bars (Open, High, Low, Close, Volume). Below is a detailed explanation of each indicator family, why it matters for trading, and exactly how it is calculated.

### Moving Averages

A **moving average** smooths out short-term price fluctuations to reveal the underlying trend. Moving averages are one of the oldest and most widely used tools in technical analysis because they transform noisy tick-by-tick data into a clear directional signal. There are two main types used here.

#### Simple Moving Average (SMA)

The SMA is the arithmetic mean of the last *n* prices:

```
SMA(n) = (P₁ + P₂ + ... + Pₙ) / n
```

For example, the SMA(5) of closing prices [10, 11, 12, 13, 14] is (10+11+12+13+14)/5 = 12.

The SMA gives equal weight to every price in the window. It reacts slowly to sudden price changes because old prices carry the same weight as new ones. SMA is used within our codebase to seed the initial RSI and ATR values, and as the divisor for volume ratio.

#### Exponential Moving Average (EMA)

The EMA gives **more weight to recent prices**, making it faster to react to new information. It uses a smoothing factor α (alpha):

```
α = 2 / (period + 1)

EMA[0] = first price  (seed value)
EMA[i] = α × Price[i] + (1 − α) × EMA[i−1]
```

The formula can be rewritten as: `EMA[i] = EMA[i−1] + α × (Price[i] − EMA[i−1])`. This makes the intuition clearer: each new EMA value is the previous EMA plus a fraction α of the "error" (how far the new price is from the old average). A higher α means faster reaction; a lower α means smoother output.

**Why EMA over SMA for the ML model?** EMA responds faster to price changes, which matters in intraday trading where you need to detect trend shifts within minutes, not hours. The exponential decay also means the EMA never fully "forgets" old prices — their influence just shrinks geometrically.

**Example — EMA(3):**

α = 2/(3+1) = 0.5. Given prices [10, 11, 12, 13, 14]:

| Step | Price | Calculation | EMA |
|------|-------|-------------|-----|
| 0 | 10 | seed | 10.000 |
| 1 | 11 | 0.5 × 11 + 0.5 × 10.0 | 10.500 |
| 2 | 12 | 0.5 × 12 + 0.5 × 10.5 | 11.250 |
| 3 | 13 | 0.5 × 13 + 0.5 × 11.25 | 12.125 |
| 4 | 14 | 0.5 × 14 + 0.5 × 12.125 | 13.063 |

Notice how EMA (13.063) is closer to the most recent price (14) than SMA (12.0) would be, because EMA weights recent prices more heavily.

**Implementation detail:** Our `ema()` function in `indicators.py` seeds with the very first price value (not an SMA seed). This is the simplest approach and converges quickly — after approximately 3×period bars, the seeding choice has negligible impact on the result. Some implementations seed with `SMA(period)` over the first N values, but the difference is immaterial for our 200-bar window.

##### EMA(20) — Short-Term Trend

EMA with a 20-bar lookback. With 1-minute bars, this captures roughly the last 20 minutes of price action. It reacts relatively quickly to price changes and is used for detecting short-term trend shifts.

- α = 2/21 ≈ 0.095 (each new price contributes ~9.5% to the average)
- **Half-life ≈ 13 bars** — after 13 bars, the weight of any single price decays to ~50% of its initial influence
- When price is above EMA(20), the short-term trend is **up**
- When price is below EMA(20), the short-term trend is **down**

**Why 20?** In a standard trading session (6.5 hours = 390 minutes), 20 minutes represents approximately 5% of the day. This captures "the current move" without being so fast that every wiggle causes whipsaw.

##### EMA(50) — Medium-Term Trend

EMA with a 50-bar lookback (~50 minutes). It's smoother and slower to react than EMA(20). Used for detecting the medium-term trend direction.

- α = 2/51 ≈ 0.039 (each new price contributes ~3.9%)
- **Half-life ≈ 34 bars**

**Why 50?** 50 minutes covers roughly the first hour of trading or the last hour — key periods where institutional flows create durable trends. A 50-bar EMA filters out noise while still being responsive enough for intraday use.

##### EMA Crossover

When the faster EMA(20) **crosses above** the slower EMA(50), it's called a **golden cross** — a bullish signal suggesting upward momentum. When EMA(20) **crosses below** EMA(50), it's a **death cross** — bearish.

The crossover signal works because:
1. In an uptrend, short-term prices rise faster than the longer average → EMA(20) > EMA(50)
2. The moment of crossing represents a shift in momentum dominance
3. The magnitude of the gap (EMA(20) − EMA(50)) indicates trend strength

The model uses five EMA-derived features:

| Feature | Formula | Interpretation |
|---------|---------|----------------|
| `ema_20` | EMA(20) of close | Short-term trend level |
| `ema_50` | EMA(50) of close | Medium-term trend level |
| `ema_cross` | EMA(20) − EMA(50) | Positive = bullish, negative = bearish. Magnitude indicates trend strength |
| `ema_20_dist` | (Close − EMA(20)) / EMA(20) | How far price has deviated from the short-term trend (mean-reversion signal). A reading of +0.005 means price is 0.5% above the 20-period average |
| `ema_50_dist` | (Close − EMA(50)) / EMA(50) | How far price has deviated from the medium-term trend |

**Why include the raw EMA values (`ema_20`, `ema_50`) and not just the derived features?** The raw values give the model access to the absolute price level, which can matter for stocks with different price magnitudes. However, the derived features (`ema_cross`, `ema_20_dist`, `ema_50_dist`) are the more informative features because they're normalized and comparable across symbols.

---

### Relative Strength Index — RSI(14)

RSI measures the **speed and magnitude** of recent price changes on a 0–100 scale. It answers the question: "Of the recent price moves, what fraction were upward?"

**Why RSI is useful for the ML model:** RSI is a momentum oscillator that identifies overbought and oversold conditions. When a stock has risen too fast (RSI > 70), there's a statistical tendency for it to pull back. When it's fallen too fast (RSI < 30), it tends to bounce. The model uses RSI as a mean-reversion signal that counterbalances the trend-following EMA features.

**Calculation (Wilder's method):**

1. Compute price changes: Δ[i] = Close[i] − Close[i−1]
2. Separate into gains (Δ > 0) and losses (|Δ| when Δ < 0)
3. Compute the first average gain and loss as the SMA over the first 14 periods
4. Smooth subsequent values using Wilder's formula:
   ```
   AvgGain[i] = (AvgGain[i−1] × 13 + CurrentGain) / 14
   AvgLoss[i] = (AvgLoss[i−1] × 13 + CurrentLoss) / 14
   ```
5. RS = AvgGain / AvgLoss
6. RSI = 100 − 100 / (1 + RS)

**Why Wilder's smoothing instead of SMA?** Wilder's formula is equivalent to an EMA with α = 1/period (= 1/14 ≈ 0.071). Compared to a standard EMA (α = 2/(period+1) ≈ 0.133), Wilder's smoothing is slower and more stable. This prevents RSI from whipsawing on every small price fluctuation. J. Welles Wilder chose this specific smoothing because it produces RSI values that reliably hover around 50 in sideways markets and reach extremes only during genuine trending periods.

**Worked example — RSI(3) for clarity:**

Prices: [44, 44.34, 44.09, 43.61, 44.33, 44.83, 45.10]

| i | Price | Change | Gain | Loss |
|---|-------|--------|------|------|
| 1 | 44.34 | +0.34 | 0.34 | 0.00 |
| 2 | 44.09 | −0.25 | 0.00 | 0.25 |
| 3 | 43.61 | −0.48 | 0.00 | 0.48 |

First averages (SMA over 3 periods):
- AvgGain = (0.34 + 0 + 0) / 3 = 0.113
- AvgLoss = (0 + 0.25 + 0.48) / 3 = 0.243

RS = 0.113 / 0.243 = 0.465 → RSI = 100 − 100/(1+0.465) = **31.7** (oversold territory)

| i | Price | Change | Wilder AvgGain | Wilder AvgLoss | RS | RSI |
|---|-------|--------|---------------|---------------|-----|-----|
| 4 | 44.33 | +0.72 | (0.113×2 + 0.72)/3 = 0.315 | (0.243×2 + 0)/3 = 0.162 | 1.944 | 66.0 |
| 5 | 44.83 | +0.50 | (0.315×2 + 0.50)/3 = 0.377 | (0.162×2 + 0)/3 = 0.108 | 3.491 | 77.7 |
| 6 | 45.10 | +0.27 | (0.377×2 + 0.27)/3 = 0.341 | (0.108×2 + 0)/3 = 0.072 | 4.736 | 82.6 |

After three consecutive gains, RSI surges from 31.7 to 82.6 — moving from oversold to overbought territory.

**Interpreting RSI:**

| RSI Range | Interpretation |
|-----------|----------------|
| > 70 | **Overbought** — price has risen too fast, potential pullback. Some traders sell here. |
| 30–70 | **Neutral zone** — no extreme reading |
| < 30 | **Oversold** — price has fallen too fast, potential bounce. Some traders buy here. |
| Approaching 50 from above | Weakening uptrend |
| Approaching 50 from below | Weakening downtrend |

RSI(14) means a 14-period lookback, which is the standard originally proposed by J. Welles Wilder in 1978. With 1-minute bars, this captures the last 14 minutes of momentum.

**Implementation note:** When there are fewer than `period + 1` bars, our code returns 50.0 (neutral) — we don't have enough data to compute a meaningful RSI, so we return a value that won't bias the model in either direction.

---

### Moving Average Convergence Divergence — MACD

MACD is a **trend-following momentum indicator** that shows the relationship between two EMAs. It consists of three components:

| Component | Formula | Meaning |
|-----------|---------|---------|
| **MACD Line** | EMA(12) − EMA(26) | Difference between fast and slow EMAs. Positive = bullish trend, negative = bearish |
| **Signal Line** | EMA(9) of the MACD Line | A smoothed version of the MACD line, used as a trigger |
| **Histogram** | MACD Line − Signal Line | The gap between the two. When it flips from negative to positive, it signals momentum shift |

**Standard parameters:** MACD(12, 26, 9) — fast EMA period 12, slow EMA period 26, signal period 9.

**Why MACD is useful:** MACD captures both trend direction AND momentum in a single indicator. The MACD line shows whether short-term prices are above or below the longer-term trend (trend direction). The histogram shows whether that divergence is growing or shrinking (momentum). This is valuable because the model gets two pieces of information:
1. **Where is the trend?** (MACD line sign)
2. **Is the trend accelerating or decelerating?** (histogram sign and magnitude)

**How to read MACD:**

1. **Signal line crossover (primary signal):** When the MACD line crosses above the signal line → bullish. When it crosses below → bearish.
2. **Centerline crossover:** When the MACD line crosses above zero (meaning EMA(12) > EMA(26)) → bullish trend confirmed.
3. **Histogram divergence:** When the histogram is growing → momentum increasing. When shrinking → momentum fading.
4. **Divergence from price:** If price makes a new high but MACD doesn't → bearish divergence (momentum is weakening even as price rises). This is one of the most reliable signals in technical analysis.

**Example:** In a steady uptrend, EMA(12) rises faster than EMA(26), so the MACD line is positive and growing. When the uptrend stalls, EMA(12) stops rising → MACD line flattens → histogram shrinks → warning of trend reversal.

**Why three separate features instead of one?** Each component captures different information:
- `macd_line` — the trend itself (direction and strength)
- `macd_signal` — the smoothed trend (less noisy)
- `macd_hist` — the rate of change of the trend (momentum of momentum)

The model can learn patterns like "MACD line is positive but histogram is shrinking" (trend still up but weakening), which is a more nuanced signal than any single value.

The model uses three MACD features: `macd_line`, `macd_signal`, `macd_hist`.

---

### Average True Range — ATR(14)

ATR measures **volatility** — how much price moves per bar, on average. Unlike standard deviation, ATR accounts for overnight gaps (when the current bar's high or low is outside the previous bar's close).

**Why ATR is useful:** Volatility is critical context for the model. A 1% price move in a low-volatility stock (ATR_norm = 0.002) is a major event, while the same move in a high-volatility stock (ATR_norm = 0.02) is normal noise. Without ATR, the model can't distinguish between a genuine signal and normal price fluctuation. ATR also tends to spike before major price moves (volatility expansion), giving the model a leading indicator.

**True Range (TR):** The largest of:
1. Current High − Current Low (intrabar range)
2. |Current High − Previous Close| (gap up)
3. |Current Low − Previous Close| (gap down)

```
TR[i] = max(H[i] − L[i], |H[i] − C[i−1]|, |L[i] − C[i−1]|)
```

**Why use True Range instead of just High − Low?** Consider a stock that closes at $100, then opens the next bar at $105 and trades between $105–$107. The High−Low range is only $2, but the actual price displacement (from $100 to anywhere in $105–$107) is at least $5. True Range captures this gap by comparing against the previous close.

**ATR** is the Wilder-smoothed average of TR over 14 periods:

```
ATR[14] = SMA(TR, 14)  for the first value
ATR[i] = (ATR[i−1] × 13 + TR[i]) / 14  for subsequent values
```

**Worked example:**

Suppose a stock has these bars:

| Bar | High | Low | Close | Prev Close | TR |
|-----|------|-----|-------|------------|-----|
| 1 | 102 | 98 | 100 | — | 4 |
| 2 | 103 | 99 | 101 | 100 | max(4, 3, 1) = 4 |
| 3 | 105 | 100 | 104 | 101 | max(5, 4, 1) = 5 |
| 4 | 104 | 97 | 98 | 104 | max(7, 0, 7) = 7 |

Bar 4 shows why True Range matters: High−Low is 7, but |Low − PrevClose| = |97 − 104| = 7 is equally large. If the stock had gapped down to open at 96, the True Range would capture that gap.

**Two ATR features used:**

| Feature | Formula | Purpose |
|---------|---------|---------|
| `atr_14` | ATR(14) raw value | Absolute volatility level — useful for comparing a stock to its own historical volatility |
| `atr_norm` | ATR(14) / Close | Normalized volatility (comparable across different-priced stocks). A $500 stock with ATR $5 has the same `atr_norm` (0.01 = 1%) as a $50 stock with ATR $0.50. This normalization is critical because the model trains on multiple symbols |

---

### Volume Ratio

Volume ratio measures whether the current bar's trading volume is above or below the recent average:

```
volume_ratio = Current Volume / SMA(20, Volume)
```

**Why volume ratio is useful:** Volume is the "conviction" behind a price move. A price increase on 3× average volume suggests strong institutional buying. The same increase on 0.5× volume might be a random fluctuation. The ML model uses volume ratio to assess the reliability of the signals from other indicators.

| Value | Interpretation |
|-------|----------------|
| > 2.0 | **Very high volume** — institutional activity, likely significant move |
| > 1.5 | **Unusually high volume** — big players are active, potential breakout or breakdown |
| 1.0 | Average volume — normal activity |
| 0.5–1.0 | Below average — quiet market |
| < 0.5 | **Low volume** — thin market, moves may be unreliable |

**Example:** If a stock typically trades 10,000 shares per minute (SMA(20) of volume = 10,000), and the current bar has 25,000 shares, the volume ratio is 2.5. Combined with a price breakout above EMA(20), this high-volume move is more likely to be sustained than a breakout on 5,000 shares (volume ratio 0.5).

---

### Realized Volatility

The rolling standard deviation of 1-bar returns over 20 periods:

```
returns[i] = (Close[i] − Close[i−1]) / Close[i−1]
realized_vol = std(returns[-20:])
```

**Why realized volatility is useful:** While ATR measures average price range, realized volatility measures the dispersion of returns. A stock can have a high ATR (large range per bar) but low realized vol (consistent direction), or low ATR but high realized vol (choppy). Realized vol captures the "noise" in price action, which is complementary to ATR's "range" measure.

**Interpretation:**
- **High realized vol** (e.g., > 0.005 = 0.5% per minute): Price is moving erratically. Trend-following signals may be unreliable. The model can learn to reduce confidence in trending signals during high-volatility regimes.
- **Low realized vol** (e.g., < 0.001 = 0.1% per minute): Price is calm and directional. Trend signals are more likely to be valid.

---

### Return Features

Two simple return features capture recent price momentum:

| Feature | Formula | Interpretation |
|---------|---------|----------------|
| `return_1` | Close / PrevClose − 1 | Last bar's return. Positive = price rose. Captures the most recent price impulse |
| `return_5` | Close / Close_5_bars_ago − 1 | 5-bar momentum. Captures short-term trend direction and speed |

**Why two return horizons?** `return_1` is very noisy — a single bar's return is dominated by randomness. `return_5` smooths out single-bar noise while still being responsive to genuine momentum shifts. The model can learn the interaction: for example, `return_1 > 0` AND `return_5 < 0` suggests a bearish bounce (short-term up within a downtrend), which might predict further decline.

**Why not include more return horizons (10, 20, etc.)?** Diminishing returns — longer-horizon returns are already captured implicitly by the EMA features. Adding more return features would increase model complexity without proportional information gain. The 15-feature vector is designed to be compact but comprehensive.

---

## Complete Feature Vector

The model receives exactly 15 features per prediction:

| # | Feature Name | Indicator Family | Description |
|---|-------------|-----------------|-------------|
| 1 | `ema_20` | Moving Average | EMA(20) of close prices |
| 2 | `ema_50` | Moving Average | EMA(50) of close prices |
| 3 | `ema_cross` | Moving Average | EMA(20) − EMA(50) |
| 4 | `ema_20_dist` | Moving Average | (Close − EMA(20)) / EMA(20) |
| 5 | `ema_50_dist` | Moving Average | (Close − EMA(50)) / EMA(50) |
| 6 | `rsi_14` | Momentum | RSI(14) |
| 7 | `macd_line` | Trend/Momentum | EMA(12) − EMA(26) of close |
| 8 | `macd_signal` | Trend/Momentum | EMA(9) of MACD line |
| 9 | `macd_hist` | Trend/Momentum | MACD line − signal line |
| 10 | `atr_14` | Volatility | ATR(14) |
| 11 | `atr_norm` | Volatility | ATR(14) / Close |
| 12 | `volume_ratio` | Volume | Volume / SMA(20, Volume) |
| 13 | `realized_vol` | Volatility | std(returns, 20 periods) |
| 14 | `return_1` | Momentum | 1-bar return |
| 15 | `return_5` | Momentum | 5-bar return |

**Feature families and their roles:**

| Family | Features | Role in Prediction |
|--------|----------|-------------------|
| **Trend** (EMA) | ema_20, ema_50, ema_cross, ema_20_dist, ema_50_dist | "Where is price going?" — direction and deviation from trend |
| **Momentum** (RSI, MACD, Returns) | rsi_14, macd_line, macd_signal, macd_hist, return_1, return_5 | "How fast and with what conviction?" — strength of price movement |
| **Volatility** (ATR, RealizedVol) | atr_14, atr_norm, realized_vol | "How noisy is this?" — context for interpreting moves |
| **Volume** | volume_ratio | "Is anyone paying attention?" — conviction behind moves |

The feature vector is intentionally designed to capture multiple complementary dimensions of market state. No single feature is sufficient for reliable prediction — the model's power comes from learning the interactions between them.

---

## Feature Engineering Pipeline

The feature engine converts raw ticks into the 15-feature vector through a two-stage pipeline:

### Stage 1: Tick Aggregation → 1-Minute OHLCV Bars

Raw market data arrives as individual tick events (trades at specific prices and volumes). These arrive irregularly — there might be 50 ticks in one second during active trading, then no ticks for 10 seconds during a lull. The feature engine aggregates these into regular 1-minute bars:

- **Open** = first trade price in the minute
- **High** = highest trade price in the minute
- **Low** = lowest trade price in the minute
- **Close** = last trade price in the minute
- **Volume** = total shares traded in the minute

For QUOTE events (bid/ask updates without trades), the engine uses the midpoint (bid + ask) / 2 as the price but does not count volume. This ensures that even in periods without trades, the price tracking stays current.

**How bar boundaries work:** Each tick's timestamp is truncated to the start of its 60-second interval (via integer division: `bar_ts = (ts // 60) * 60`). When a tick arrives whose truncated timestamp differs from the current in-progress bar, the previous bar is "closed" and added to the rolling history. The new tick starts a fresh bar.

**Example:**

```
09:30:00.100  TRADE AAPL $150.00 x 500   → BarBuilder starts: O=150.00
09:30:15.200  TRADE AAPL $150.25 x 300   → BarBuilder updates: H=150.25, C=150.25
09:30:45.800  TRADE AAPL $149.80 x 200   → BarBuilder updates: L=149.80, C=149.80
09:31:00.050  TRADE AAPL $150.10 x 100   → Bar 09:30 completed: O=150, H=150.25, L=149.8, C=149.8, V=1000
                                             BarBuilder starts for 09:31: O=150.10
```

**Rolling window:** The engine retains up to 200 completed bars per symbol (configurable via `max_bars_retained`). When the 201st bar arrives, the oldest is discarded. This 200-bar window provides enough history for all indicators:
- EMA(50) needs ~150 bars to stabilize (3× period rule of thumb)
- RSI(14), ATR(14) need ~28 bars (2× period)
- MACD(12,26,9) needs ~78 bars (3× slow period)

### Stage 2: Indicator Computation

When a bar closes and the symbol has accumulated at least 50 bars (needed for EMA(50)), all 15 features are recomputed from the full bar history using NumPy vectorized operations. The latest feature values are stored and become available to gRPC callers.

**Why recompute from scratch instead of maintaining incremental state?** 

Incremental computation (updating EMA/RSI values bar-by-bar) would be faster, but introduces two risks:
1. **Floating-point drift** — after thousands of incremental updates, accumulated rounding errors can cause incremental values to diverge from the "true" values
2. **Recovery complexity** — if the service restarts or a bar is missed, incremental state is lost and must be reconstructed

Full recomputation from the 200-bar window avoids both issues. With NumPy, 200 bars × 15 features takes under 1ms — well within the 50ms p99 latency budget. The simplicity is worth the negligible performance cost.

**Feature staleness:** After computing features, the engine records the current wall-clock time. The `/metrics` endpoint reports `mariaalpha_ml_feature_staleness_seconds` — how many seconds ago features were last updated for each symbol. If this exceeds ~2 minutes (no bars closing), the features may be based on stale data and the model's predictions become less reliable.

---

## The LightGBM Model

### What is LightGBM?

**LightGBM** (Light Gradient Boosting Machine) is a high-performance implementation of **gradient-boosted decision trees (GBDT)**, developed by Microsoft. It's one of the most popular ML algorithms for tabular/structured data — it consistently wins Kaggle competitions on structured datasets and is the standard choice for financial feature-based prediction.

### How Gradient Boosting Works

Gradient boosting is an **ensemble method** — it combines many weak learners (shallow decision trees) into one strong predictor. Here's the process:

1. **Initialize** with a simple prediction (e.g., "50% chance of going up" for all samples).
2. **Compute residuals** — for each training sample, calculate the error between the current prediction and the true label.
3. **Fit a decision tree** to the residuals — this tree learns to correct the current model's mistakes.
4. **Add the tree** to the ensemble with a small weight (learning rate = 0.05). The prediction becomes: old prediction + 0.05 × tree's correction.
5. **Repeat** steps 2–4 for N rounds (200 trees in our configuration).
6. **Final prediction** = sum of all trees' contributions, passed through a sigmoid function to get a probability.

**Why the learning rate is small (0.05):** A small learning rate means each tree contributes a small correction. This prevents overfitting — the model builds up its prediction gradually over many trees rather than fitting the training data aggressively with a few trees. The tradeoff: more trees needed (slower training), but better generalization to unseen data.

**What each tree looks like:**

Each tree is a series of if/else splits on the features. For example:
```
if RSI > 70:
    if volume_ratio > 1.5:
        predict: slight downward bias (overbought + high volume = distribution)
    else:
        predict: neutral (overbought but low volume = no conviction)
else if ema_cross > 0 and macd_hist > 0:
    predict: slight upward bias (trend + momentum aligned)
```

The trees are "shallow" — our configuration uses `num_leaves=31`, which means each tree can have at most 31 terminal nodes (approximately 5 levels deep). This prevents any single tree from memorizing training data.

### Why LightGBM for This Use Case

| Property | Benefit for Trading Signals |
|----------|---------------------------|
| Handles numerical features natively | No preprocessing needed — tree splits work directly on raw feature values |
| Fast inference (< 1ms) | Must respond within the strategy engine's 500ms gRPC deadline |
| Small model size (< 100 KB) | Easy to deploy, version, and hot-reload |
| Handles non-linear interactions | Can learn that "RSI > 70 AND volume_ratio > 1.5" is bearish, which linear models can't express |
| Robust to feature scale | Doesn't require feature normalization (unlike neural networks) — tree splits are scale-invariant |
| Feature importance built-in | Can identify which features contribute most to predictions |
| Handles missing values | If a feature is NaN, the tree routes down a default path (important for the first 50 bars where some indicators aren't available) |

**Why not a neural network?** For 15 tabular features, gradient-boosted trees are empirically superior to neural networks. Neural networks excel at unstructured data (images, text, audio) but underperform on small tabular datasets. Our training set is modest (millions of 1-minute bars × 5 symbols), and the features are hand-engineered with domain knowledge — exactly the setting where tree-based models shine.

### Training Process

Training is the process of teaching the model to map the 15-feature vector to a prediction. The training script (`scripts/train_signal_model.py`) performs these steps:

#### Step 1: Fetch Historical Data

Download 6 months of 1-minute bars from Alpaca's historical data API for five liquid stocks: AAPL, MSFT, GOOGL, AMZN, META.

**Why these symbols?** They're among the most liquid US equities with tight spreads and deep order books. The model needs liquid stocks because:
- Price data is reliable (no stale quotes)
- The signals will actually be tradeable (you can get fills)
- Volume patterns are consistent (institutional participation is regular)

**Why 6 months?** Enough to capture multiple market regimes (trending, mean-reverting, high-volatility events) without going so far back that the patterns are no longer relevant. Market microstructure changes over time, so very old data may teach the model outdated patterns.

#### Step 2: Compute Features

Apply the same 15 indicator functions used during live inference. This is critical: **training and inference must use identical feature computation code**. Our training script imports from `ml_signal.features.indicators`, ensuring the same EMA, RSI, MACD, and ATR implementations are used in both contexts.

The first 50 bars per symbol are dropped (warm-up period) because indicators like EMA(50) haven't stabilized yet.

#### Step 3: Generate Labels

For each bar, compute the return 5 bars ahead:

```
label[i] = 1 if Close[i+5] > Close[i] else 0
```

This is a **binary classification** task: "will the price be higher in 5 minutes?" The 5-minute horizon was chosen because:
- **1 minute** would be too noisy — transaction costs would dominate any small edge
- **5 minutes** is the sweet spot for intraday signals — enough time for a trend to develop, short enough that our features are still relevant
- **30+ minutes** would make the 1-minute features too stale — daily features would be needed

The last 5 bars per symbol have no future label and are dropped.

#### Step 4: Train/Test Split

Use the first 80% of data for training, the last 20% for validation. This is a **time-ordered split** (not random) to avoid **look-ahead bias**. In a random split, the model could train on Tuesday's data and test on Monday's — but in real trading, you never have future data. The time-ordered split simulates realistic conditions.

#### Step 5: Train the Model

```python
LGBMClassifier(
    n_estimators=200,      # 200 trees in the ensemble
    num_leaves=31,         # max complexity per tree (default, good balance)
    learning_rate=0.05,    # small steps for better generalization
    feature_fraction=0.9,  # each tree sees 90% of features (prevents over-reliance on one feature)
    bagging_fraction=0.8,  # each tree trains on 80% of data (reduces variance)
    bagging_freq=5,        # re-sample every 5 iterations
)
```

**Key hyperparameters explained:**

- `n_estimators=200`: More trees = more capacity to learn patterns. 200 is moderate — enough for 15 features without overfitting.
- `num_leaves=31`: Controls tree complexity. A tree with 31 leaves can model up to 31 distinct "regimes" in the feature space. Higher values fit training data better but risk overfitting.
- `feature_fraction=0.9`: At each tree, randomly exclude 10% of features. This is a regularization technique borrowed from random forests — it forces the model to be robust even if some features are temporarily uninformative.
- `bagging_fraction=0.8`: Each tree trains on a random 80% subset of the training data. This reduces variance (the model doesn't overfit to any single subset of training examples).

#### Step 6: Evaluate

Measure accuracy on the validation set. Target: > 50% (better than random coin flip). In practice, even 52–55% accuracy can be profitable when combined with proper position sizing and risk management. Here's why:

- With 55% accuracy and 1:1 risk/reward ratio: Expected value per trade = 0.55 × Win − 0.45 × Loss = 0.55 − 0.45 = +0.10 (10% edge per trade)
- Over hundreds of trades per day, this small edge compounds significantly
- The model's confidence score allows position sizing — bet more when confidence is high, less when low

The training script also prints a **classification report** showing precision, recall, and F1-score for each class (UP / DOWN-FLAT), and **feature importance** (which features contributed most to the model's decisions).

#### Step 7: Save the Model Artifact

The trained model is serialized using `joblib.dump()`:

```python
joblib.dump({
    "model": clf,              # the trained LGBMClassifier object
    "version": "20260413-...", # timestamp-based version string
    "feature_names": [...],    # ordered list of 15 feature names
    "accuracy": 0.543,         # validation accuracy for reference
    "n_train": 450000,         # number of training samples
    "n_val": 112000,           # number of validation samples
    "symbols": ["AAPL", ...],  # symbols used for training
    "label_horizon": 5,        # prediction horizon in bars
}, "ml-models/signal_model.joblib")
```

**What is joblib?** `joblib` is a Python serialization library optimized for NumPy arrays. It uses `pickle` under the hood but compresses and handles large arrays efficiently. The resulting `.joblib` file contains the entire model state — all 200 trees, their split thresholds, leaf values, and metadata. Typical size: 50–150 KB.

**What's inside the saved artifact:**
- The `model` key contains a `LGBMClassifier` instance. This object contains: (a) the 200 decision trees as internal data structures, (b) the learned split thresholds for each feature, (c) the leaf values that determine predictions, (d) hyperparameters used during training.
- The `feature_names` list ensures the model receives features in the correct order during inference. If features were reordered, the model would apply the wrong split thresholds to the wrong features.
- The `version` string enables tracking which model is deployed. The `/v1/models/reload` endpoint returns the version before and after reload.

**How model versioning works:** Each training run produces a timestamped version (e.g., `20260413-143052`). The file is always saved to `ml-models/signal_model.joblib`. To deploy a new model:
1. Run the training script (overwrites the file)
2. Either restart the service, or call `POST /v1/models/reload` for a live swap

### Inference

During live operation, inference takes < 1ms:

1. Receive a `GetSignal(symbol)` gRPC call.
2. Look up the latest feature vector for that symbol from the feature engine.
3. Build a NumPy array with the 15 features in the correct order (matching `feature_names`).
4. Pass the array to the LightGBM model's `predict_proba()` method.
5. The model evaluates the input through all 200 trees: each tree routes the feature vector down its branches based on split thresholds, arriving at a leaf that contributes a partial prediction. The 200 partial predictions are summed and passed through a sigmoid function.
6. The result is `P(positive_return)` — the probability of the price going up in the next 5 minutes.
7. Map the probability to direction + confidence:

| Probability | Direction | Confidence |
|-------------|-----------|------------|
| > 0.55 | LONG | probability value |
| < 0.45 | SHORT | 1 − probability |
| 0.45–0.55 | NEUTRAL | 0.5 |

**Why 0.55/0.45 thresholds instead of 0.50?** A 5% dead zone around 50% prevents the model from generating signals when it has no meaningful conviction. Near 50/50, the prediction is essentially noise. The dead zone ensures we only signal when the model has at least a modest statistical edge.

---

## End-to-End Signal Production Flow

Here's the complete journey of a market tick through the system, from raw data to a trading decision:

```
1. Alpaca WebSocket → market-data-gateway → Kafka "market-data.ticks"
   │
   │  (raw tick: {"symbol":"AAPL", "tradePrice":150.25, "tradeVolume":500, "timestamp":"..."})
   │
2. TickConsumer (background thread) polls Kafka
   │  └─ Deserializes JSON, extracts price and volume
   │
3. FeatureEngine.on_tick()  [acquires _lock]
   │  ├─ Is this tick in the same minute as the current bar? → Update BarBuilder
   │  └─ Different minute? → Close current bar, start new one
   │     └─ >= 50 completed bars? → _compute_features()
   │        ├─ Compute all 15 indicators from the bar history
   │        ├─ Store feature dict in _features["AAPL"]
   │        └─ _notify_listeners() → push to StreamSignals queues
   │
   ▼
4. Strategy engine (Java) detects a VWAP signal for AAPL
   │  └─ Calls gRPC: GetSignal("AAPL") with 500ms deadline
   │
5. SignalServicer.GetSignal()  [gRPC thread pool]
   │  ├─ feature_engine.get_features("AAPL")  [acquires _lock]
   │  │  └─ Returns the 15-feature dict (or None if < 50 bars)
   │  ├─ signal_model.predict(features)  [acquires model _lock]
   │  │  ├─ Build numpy array from feature dict
   │  │  ├─ model.predict_proba() → P(up) = 0.72
   │  │  ├─ P(up) > 0.55 → direction = LONG, confidence = 0.72
   │  │  └─ position_size = min(0.10, max(0.0, (0.72 − 0.5) × 0.2)) = 0.044
   │  └─ Return SignalResponse(symbol="AAPL", direction=LONG, confidence=0.72, ...)
   │
6. Strategy engine receives ML signal
   │  ├─ Confidence 0.72 > threshold 0.70? YES
   │  ├─ Direction LONG agrees with VWAP BUY signal? YES
   │  └─ → Signal allowed through, published to Kafka "orders.lifecycle"
```

If the ML direction was SHORT (contradicting the VWAP BUY), the signal would be suppressed.

---

## Signal Generation

### Direction

The signal direction tells the strategy engine which way the model thinks the price will move:

- **LONG** — model predicts the price will rise in the next 5 minutes (P(up) > 0.55)
- **SHORT** — model predicts the price will fall (P(up) < 0.45, equivalently P(down) > 0.55)
- **NEUTRAL** — model is uncertain (probability near 50/50, between 0.45 and 0.55)

### Confidence

A score from 0.0 to 1.0 indicating how certain the model is. The confidence is the probability itself for LONG signals and (1 − probability) for SHORT signals, ensuring confidence always reflects conviction regardless of direction.

The strategy engine uses a **confidence threshold** (default 0.7):

- Confidence > 0.7 **and** direction agrees with strategy → proceed with signal
- Confidence > 0.7 **and** direction contradicts strategy → **suppress the signal**
- Confidence ≤ 0.7 → proceed with the strategy signal regardless (ML signal is too weak to override)
- ML service unavailable → proceed without ML input

**Why 0.7 as the suppression threshold?** It represents a meaningful conviction level — the model is 70% sure of its prediction. Below 0.7, the model's signal is considered informational but not strong enough to override a strategy that has its own logic. Above 0.7, the model has enough statistical evidence to warrant overriding a potentially bad trade.

### Recommended Position Size

A fraction of available capital (0.0–0.10) suggesting how much to allocate. Scales linearly with confidence:

```
position_size = min(0.10, max(0.0, (confidence − 0.5) × 0.2))
```

| Confidence | Position Size | Explanation |
|------------|---------------|-------------|
| 0.50 | 0.0% | No conviction → no position |
| 0.55 | 1.0% | Minimal conviction |
| 0.60 | 2.0% | Low conviction |
| 0.65 | 3.0% | Moderate conviction |
| 0.70 | 4.0% | Confident |
| 0.75 | 5.0% | High confidence |
| 0.80 | 6.0% | Very confident |
| 0.90 | 8.0% | Extremely confident |
| 1.00 | 10.0% | Maximum position (capped) |

The 10% cap prevents the model from concentrating too much capital in a single position, even with maximum confidence. This is a risk management safeguard — no single trade should represent more than 10% of capital.

---

## gRPC Interface

The service implements the `SignalService` defined in `proto/src/main/proto/signal.proto`:

### GetSignal (Unary RPC)

```protobuf
rpc GetSignal(SignalRequest) returns (SignalResponse);
```

Called by the strategy engine's `MlSignalClient` before acting on each order signal. Returns the current prediction for a symbol. If no features are available yet (not enough bars), returns NEUTRAL with confidence 0.

The response includes the full feature map (`map<string, double> features = 6`) so that downstream consumers can log or audit which features drove the prediction. This field is backward-compatible — the Java strategy engine ignores it (it only reads `direction` and `confidence`).

### GetRegime (Unary RPC) — Phase 2

```protobuf
rpc GetRegime(RegimeRequest) returns (RegimeResponse);
```

Returns the current market regime classification. In Phase 1, this always returns `UNKNOWN` with confidence 0. Phase 2 (issue 2.3.1) will implement a Random Forest regime classifier that categorizes the market as TRENDING_UP, TRENDING_DOWN, MEAN_REVERTING, HIGH_VOLATILITY, or LOW_VOLATILITY.

**Why include it now?** The gRPC contract (proto definition) should be stable from Phase 1. Adding RPCs later would require regenerating all language stubs and potentially breaking existing clients. By including the RPC now with a stub implementation, the proto is future-proof.

### StreamSignals (Server-Streaming RPC)

```protobuf
rpc StreamSignals(SignalStreamRequest) returns (stream SignalResponse);
```

A push-based alternative to polling `GetSignal`. The client opens a stream and specifies which symbols to watch (or all symbols if the list is empty). The server pushes a new `SignalResponse` every time the feature engine completes a new bar and recomputes features. This avoids the latency of request/response round-trips.

**How it works internally:**

1. Client calls `StreamSignals(symbols=["AAPL", "MSFT"])`.
2. The servicer creates a `queue.Queue(maxsize=100)` for this client.
3. The queue is registered with the feature engine via `add_listener()`.
4. The servicer enters a loop: `while context.is_active()`, blocking on `queue.get(timeout=1.0)`.
5. When the feature engine computes new features for AAPL, it calls `_notify_listeners()`, which puts `("AAPL", features_dict)` into all matching queues.
6. The servicer picks up the item, runs model inference, and `yield`s a `SignalResponse`.
7. When the client disconnects, `context.is_active()` returns False, the loop exits, and the `finally` block removes the listener and decrements the active client gauge.

**Backpressure:** If a client is slow and its queue fills up (100 items), the feature engine silently drops updates for that client via `contextlib.suppress(queue.Full)`. The client simply receives the next available update. This prevents a slow consumer from blocking the entire feature pipeline.

---

## How the Strategy Engine Uses ML Signals

The integration point is in `StrategyEvaluationService.evaluate()`:

```
1. Tick arrives from Kafka → strategy.onTick(tick)
2. Strategy evaluates → Optional<OrderSignal>
3. If signal present:
   a. Call ML service: MlSignalClient.getSignal(symbol) → Optional<MlSignalResult>
   b. If ML confidence > 0.7 AND direction contradicts → SUPPRESS signal
   c. Otherwise → PUBLISH signal to Kafka (orders.lifecycle)
```

The ML service is **advisory, not authoritative**. If the ML service is down, the circuit breaker opens after 5 failed calls and the strategy engine proceeds without ML input. This is a deliberate design choice: the trading strategy is the primary decision-maker, and the ML model is a secondary filter.

**The `shouldSuppress` logic in detail:**

```
shouldSuppress(signal, mlResult):
  if mlResult is empty → false (ML unavailable, allow signal)
  if mlResult.confidence <= threshold → false (low confidence, allow signal)
  if directions agree → false (ML confirms strategy, allow signal)
  return true (high confidence + disagreement → suppress)
```

Direction agreement is defined as:
- BUY (strategy) + LONG (ML) → agree
- SELL (strategy) + SHORT (ML) → agree
- Any direction + NEUTRAL (ML) → agree (neutral never contradicts)

---

## Thread Safety

The service uses three threads (plus the gRPC thread pool) that share mutable state. Thread safety is achieved through explicit locking.

### Shared State and Lock Design

| Shared State | Owner | Lock | Writers | Readers |
|-------------|-------|------|---------|---------|
| `_bars`, `_current_bar`, `_features`, `_last_update`, `_listeners` | FeatureEngine | `FeatureEngine._lock` | Kafka consumer thread (via `on_tick`) | gRPC threads (via `get_features`, `symbols_with_features`), FastAPI thread (via `last_update_times`) |
| `_model`, `_version`, `_feature_names`, `_loaded_at` | SignalModel | `SignalModel._lock` | FastAPI thread (via `reload`), Init (via `_load`) | gRPC threads (via `predict`, `is_loaded`, `version`) |

### Why `threading.Lock` (not `RLock`)?

A `threading.Lock` is non-reentrant — if a thread holding the lock tries to acquire it again, it deadlocks. We use `Lock` because:
1. No method in our codebase acquires the same lock twice in the same call chain. The lock boundaries are clear: `on_tick` acquires once, does all work, releases.
2. `Lock` is faster than `RLock` (~20% in CPython) because `RLock` must track the owning thread and acquisition count.
3. Non-reentrant locking makes deadlocks easier to detect during development — a reentrant lock would silently succeed where a non-reentrant lock deadlocks, masking a design problem.

### Lock Granularity

The FeatureEngine and SignalModel use **separate** locks. This is intentional:
- A gRPC `GetSignal` call acquires `FeatureEngine._lock` briefly (to copy the feature dict), releases it, then acquires `SignalModel._lock` for inference. At no point does it hold both locks simultaneously.
- The Kafka consumer holds `FeatureEngine._lock` during `on_tick` (including feature computation). This blocks gRPC reads briefly (< 1ms for computation).
- Model reload acquires only `SignalModel._lock`, so it doesn't block feature computation or reads.

**No deadlock risk:** The two locks are never held simultaneously by any thread. If they were, deadlock could occur if two threads acquired them in different orders. The current design avoids this entirely.

### Performance Implications

The `FeatureEngine._lock` is the potential bottleneck: the consumer thread holds it during bar completion + feature computation (~1ms), during which gRPC threads block. With 1-minute bars, this 1ms lock acquisition happens at most once per minute per symbol. With gRPC p99 latency budget of 50ms, this is negligible.

The `SignalModel._lock` is held during `predict_proba()` (~0.5ms) and during model reload (~10ms to deserialize). Reload is rare (manual trigger only), so this doesn't affect normal operation.

### Thread Safety of Listener Notification

`_notify_listeners()` is called **inside** the `FeatureEngine._lock`. This means:
- The listener list can't be modified (add/remove) while notifications are in flight
- `queue.put_nowait()` on a bounded queue never blocks (it raises `queue.Full` immediately if full, which we suppress)
- Total notification time = number of listeners × O(1) per queue put

---

## Scalability

### Current Architecture Limits

The single-process design handles the Phase 1 workload comfortably:

| Dimension | Current Capacity | Bottleneck |
|-----------|-----------------|------------|
| **Symbols** | ~50–100 concurrent symbols | FeatureEngine lock contention (one lock for all symbols) |
| **Tick throughput** | ~10,000 ticks/sec | Single Kafka consumer thread, GIL |
| **gRPC throughput** | ~2,000 requests/sec | GIL + lock contention on feature reads |
| **StreamSignals clients** | ~50 concurrent streams | Queue notification overhead |

For Phase 1 (5–10 actively traded symbols), this is more than sufficient.

### How to Scale Horizontally (Phase 2+)

If MariaAlpha expands to hundreds of symbols or requires lower latency:

**1. Shard by symbol across multiple service instances:**
- Partition the Kafka `market-data.ticks` topic by symbol (use symbol as the partition key)
- Run N instances of the ML Signal Service, each consuming a subset of partitions
- Use a gRPC load balancer (or service mesh like Istio) to route `GetSignal("AAPL")` to the instance that has AAPL's features
- This eliminates the single-lock bottleneck because each instance manages fewer symbols

**2. Separate feature computation from model serving:**
- Run FeatureEngine instances that write computed features to a shared store (Redis, or a Kafka features topic)
- Run stateless model-serving instances that read features from the store and run inference
- This decouples the compute-heavy feature pipeline from the latency-sensitive inference path

**3. Use per-symbol locks instead of a global lock:**
- Replace `self._lock` with `self._locks: dict[str, threading.Lock]` (one lock per symbol)
- This allows concurrent feature computation for different symbols
- Complexity cost: must handle lock creation for new symbols atomically

**4. Move inference to a dedicated serving framework:**
- Export the LightGBM model to ONNX format
- Serve via ONNX Runtime, TensorRT, or Triton Inference Server
- These frameworks support batching (multiple predictions in one GPU pass) and model versioning natively

### What About the GIL?

Python's Global Interpreter Lock (GIL) means only one thread executes Python bytecode at a time. However, this is mitigated in our case:
- NumPy operations release the GIL during C-level computation
- LightGBM's `predict_proba()` releases the GIL during the C++ tree evaluation
- gRPC's C core handles network I/O without the GIL
- The main bottleneck (feature computation) is mostly NumPy/C code, not pure Python

For truly CPU-bound scaling, the sharding approach (multiple processes) is the right solution, not threading.

---

## Resilience

| Mechanism | Implementation |
|-----------|----------------|
| **Circuit breaker** (strategy-engine side) | Resilience4j: 5 failures → open for 30s. When open, strategy proceeds without ML signal |
| **gRPC deadline** (strategy-engine side) | 500ms deadline on each `GetSignal` call. On timeout, returns empty |
| **Graceful degradation** (ML service side) | If model file missing at startup, service returns NEUTRAL with confidence 0 |
| **Atomic model reload** | Model reload acquires a lock, deserializes the new model, replaces the pointer atomically. No partial state is ever visible to readers |
| **Consumer resilience** | Kafka consumer polls in a loop with error handling. Transient errors (broker disconnect, rebalance) are logged and skipped. The consumer automatically reconnects via librdkafka |
| **Feature staleness** | If features go stale (no bar closes for > 2 minutes), the gRPC response still returns the last computed features. The strategy engine doesn't know the features are stale, but the confidence will naturally be lower because the model was trained on fresh data |

### Failure Modes and Recovery

| Failure | Impact | Recovery |
|---------|--------|----------|
| ML service crashes | Strategy engine circuit breaker opens → trades without ML | Service restarts, consumer replays from Kafka offset, features rebuild after 50+ bars |
| Kafka broker down | TickConsumer.poll() returns None → no new features | Consumer reconnects automatically when broker returns; features computed from next bar onward |
| Model file corrupt | `_load()` catches exception, logs error, model stays None → returns NEUTRAL | Fix model file, call `/v1/models/reload` or restart service |
| gRPC thread pool saturated | New RPCs queued or rejected | Increase `grpc_max_workers` or scale horizontally |
| Memory pressure | Feature engine stores up to 200 bars × N symbols | Reduce `max_bars_retained` or shard by symbol |

---

## Metrics and Monitoring

The service exposes Prometheus metrics at `GET /metrics` (port 8090):

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `mariaalpha_ml_inference_duration_seconds` | Histogram | `model` | Time to run model prediction. Buckets from 0.5ms to 250ms. Alerts if p99 > 10ms |
| `mariaalpha_ml_feature_staleness_seconds` | Gauge | `symbol` | Seconds since last feature update per symbol. Should be < 120s during market hours |
| `mariaalpha_ml_ticks_consumed_total` | Counter | `symbol`, `event_type` | Ticks consumed from Kafka. Use `rate()` to monitor tick throughput. A sudden drop indicates Kafka or upstream issues |
| `mariaalpha_ml_features_computed_total` | Counter | `symbol` | Feature vectors computed (one per bar close). Should increment ~1/min per symbol during market hours |
| `mariaalpha_ml_model_info` | Info | — | Currently loaded model version and path. Use to verify the correct model is deployed |
| `mariaalpha_ml_grpc_requests_total` | Counter | `method` | gRPC requests by method name (`GetSignal`, `GetRegime`, `StreamSignals`). Monitor for traffic patterns |
| `mariaalpha_ml_stream_clients_active` | Gauge | — | Number of active StreamSignals connections. Should match expected number of stream consumers |

### Key Dashboard Panels

For a Grafana dashboard monitoring this service:

1. **Inference latency** — `histogram_quantile(0.99, rate(mariaalpha_ml_inference_duration_seconds_bucket[5m]))` — should stay under 5ms
2. **Tick throughput** — `sum(rate(mariaalpha_ml_ticks_consumed_total[1m]))` — should match the market data gateway's output rate
3. **Feature freshness** — `max(mariaalpha_ml_feature_staleness_seconds)` — alert if > 120s during market hours (09:30–16:00 ET)
4. **Model version** — `mariaalpha_ml_model_info` — verify the expected version is deployed across all instances
5. **gRPC error rate** — derive from strategy-engine-side metrics (gRPC status codes)

---

## Configuration

All settings are controlled via environment variables prefixed with `ML_SIGNAL_`:

| Variable | Default | Description |
|----------|---------|-------------|
| `ML_SIGNAL_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker addresses |
| `ML_SIGNAL_KAFKA_TICKS_TOPIC` | `market-data.ticks` | Topic to consume ticks from |
| `ML_SIGNAL_KAFKA_CONSUMER_GROUP` | `ml-signal-service` | Kafka consumer group ID |
| `ML_SIGNAL_GRPC_PORT` | `50051` | gRPC server listen port |
| `ML_SIGNAL_GRPC_MAX_WORKERS` | `10` | gRPC thread pool size |
| `ML_SIGNAL_API_PORT` | `8090` | FastAPI HTTP listen port |
| `ML_SIGNAL_SIGNAL_MODEL_PATH` | `ml-models/signal_model.joblib` | Path to the trained model file |
| `ML_SIGNAL_BAR_INTERVAL_SECONDS` | `60` | Bar aggregation interval |
| `ML_SIGNAL_MIN_BARS_FOR_FEATURES` | `50` | Minimum bars before computing features |
| `ML_SIGNAL_MAX_BARS_RETAINED` | `200` | Maximum bars retained in memory per symbol |

Configuration is loaded via `pydantic-settings`, which validates types and applies defaults automatically. Invalid values (e.g., non-integer port) cause a startup error with a clear message.
