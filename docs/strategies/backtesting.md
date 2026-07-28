# Backtesting: Concepts and MariaAlpha Implementation

> Roadmap **5.1.1** — *Implement backtesting engine (historical replay)*. Delivered.

## 1. What the backtester does

The backtester answers the one question the rest of the system cannot: **do the strategies
actually make money on historical data?** It replays real market history through the *shipped*
strategy code, simulates fills, and accounts P&L into an equity curve plus headline metrics
(total return, Sharpe, max drawdown, hit rate) and execution-quality numbers (slippage vs.
arrival). Every run also writes a self-contained HTML report.

It lives entirely in `strategy-engine` (`com.mariaalpha.strategyengine.backtest`) and reuses the
real `TradingStrategy` beans, so a backtest validates production behaviour rather than a parallel
model.

## 2. Real data in: Alpaca minute bars → a synthetic tick tape

The strategies are **tick-driven** (`onTick(MarketTick)` with bid/ask and sub-second timestamps),
but Alpaca's free IEX historical API serves **1-minute OHLCV bars**. The bridge is two components:

- **`AlpacaHistoricalClient`** — pages `/v2/stocks/{symbol}/bars?timeframe=1Min&feed=iex` back over
  the requested window (default: `lookbackDays` calendar days from now). This is the only piece
  that needs network + Alpaca credentials.
- **`BarToTickSynthesizer`** — expands each bar into four ordered `MarketTick`s. The intrabar path
  follows the conventional shape (up bars go open→low→high→close, down bars open→high→low→close),
  the bar's volume is split across the four trades, and a modelled bid/ask spread (`spreadBps`) is
  synthesized around each price since bars carry no quotes.

> **Fidelity ceiling.** Because the spread is *modelled*, not real, execution-quality metrics
> (slippage / implementation shortfall vs. arrival) are solid, but **alpha P&L is "indicative"** —
> a resting limit fills the instant price touches it, with no queue position or adverse selection.
> A real trades+quotes tape (paid Alpaca SIP) is the follow-on that removes this caveat. Every
> result carries this note in its `dataNote` field and report footer.

## 3. The virtual clock

Determinism is the whole point of a backtester, so time is **data, not wall-clock**. A
`SimulationClock` (a mutable `java.time.Clock`) is advanced to each replayed tick's timestamp; any
time-stamped output reads from it. The same idiom already exists in the RFQ engine, and the two
algo services (`AlgoOrderService`, `AlgoProgressPublisher`) were switched to an injected `Clock`
as part of this work. Two identical runs produce byte-identical results.

## 4. Fill simulation and P&L

- **`BacktestFillModel`** mirrors the production `SimulatedExchangeAdapter`: MARKET and marketable
  LIMIT orders cross the spread and pay `slippageBps` against the touch; a non-marketable LIMIT
  rests and fills passively at its limit price once a later tick prints through it. Fills are
  full-size (partial fills are intentionally out of scope so P&L stays unambiguous).
- **`BacktestPortfolio`** does average-cost, signed-position accounting: each fill updates cash and
  realises P&L on any quantity that closes/reduces a position. Equity is marked to market as
  `cash + Σ netQty·mark`, which equals `initialCash + realized + unrealized` by construction.
- **`BacktestMetricsCalculator`** derives total return, annualised Sharpe (from per-minute equity
  returns), and max drawdown from the equity curve.

Each symbol gets its **own fresh strategy instance** (reflectively constructed) so a backtest never
mutates the live singletons' state.

The ML confirm/veto gate is **off by default** (deterministic, no gRPC dependency); set
`useMlGate: true` to route signals through the live `ml-signal-service` — the hook for the future
5.1.2 A/B audit.

## 5. Running it

`POST /api/backtest` (via the API gateway, `X-API-Key` required):

```bash
curl -fsS -X POST -H "X-API-Key: local-dev-key" -H "Content-Type: application/json" \
  http://localhost:8080/api/backtest -d '{
    "strategyName": "MOMENTUM",
    "symbols": ["AAPL"],
    "lookbackDays": 5,
    "parameters": { "tradeQuantity": 100, "side": "BUY", "volumeMultiplier": 0.0 }
  }'
```

The JSON response carries the metrics, equity curve, trade blotter, and the on-disk `reportPath`.
Open the rendered report in a browser:

```bash
open http://localhost:8080/api/backtest/report
```

Requires `ALPACA_API_KEY_ID` / `ALPACA_API_SECRET_KEY` in the strategy-engine environment (the same
keys the market-data gateway's `alpaca` profile uses). The free IEX feed excludes weekends,
holidays, and very recent data, so choose a `lookbackDays` that covers a full trading session.

### Request fields

| Field | Required | Default | Meaning |
| --- | --- | --- | --- |
| `strategyName` | yes | — | Registered strategy (e.g. `MOMENTUM`, `VWAP`, `TWAP`). |
| `symbols` | yes | — | One fresh strategy instance per symbol. |
| `lookbackDays` | no | `5` | Days of history back from now (ignored if `from` is set). |
| `from` / `to` | no | now − lookback / now | Explicit ISO-8601 window. |
| `parameters` | no | `{}` | Strategy parameter overrides. |
| `useMlGate` | no | `false` | Route signals through the live ML gate (non-deterministic). |
| `slippageBps` | no | config | Override modelled slippage. |
| `initialCash` | no | `100000` | Starting cash. |

## 6. What it found (worked example)

Run against real Alpaca IEX bars for the recent window, the backtester tells an honest story rather
than a flattering one:

- The **naive** `MOMENTUM` config with the volume filter disabled (`volumeMultiplier: 0.0`, the
  deterministic setup the unit tests pin) fires on every fast/slow crossover. Over a ~15-day window
  it turns ~170 round-trips on each liquid tech name (AAPL/MSFT/NVDA/TSLA/AMZN) and **loses 3–8 %** —
  a textbook death-by-a-thousand-cuts, eaten by the modelled 2 bps spread/slippage on every fill.
- A **disciplined** config — wider periods (`fastPeriod: 15`, `slowPeriod: 60`), a real volume
  filter (`volumeMultiplier: 1.5`), a `stopLossPct: 0.5`, and a longer `warmupTrades: 60` — cuts
  AAPL to **30 trades and a positive +0.19 % return at Sharpe ≈ 2** over the same window, while
  NVDA/MSFT stop bleeding. Same code, same data — only the parameters changed.

That contrast *is* the point of the tool: it distinguishes a strategy that makes money from one that
merely trades. (Absolute numbers are "indicative" per §2; the ranking and the overtrading signal are
robust.)

## 7. Scope & limitations

- **Execution-quality backtesting is credible today; alpha P&L is indicative** (see §2).
- **Regular-hours 1-minute resolution** — the free IEX feed. No sub-minute microstructure.
- **No borrow/financing/commission model** beyond configurable slippage.
- The intraday execution algos (VWAP/TWAP/POV/IS/Close) work a single-day parent window; Momentum
  is the natural directional strategy for a multi-day equity-curve narrative.
