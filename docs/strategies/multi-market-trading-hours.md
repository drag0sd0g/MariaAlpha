# Multi-Market Trading Hours

> **Roadmap:** [3.1.3 — Implement multi-market trading hours support](https://github.com/drag0sd0g/MariaAlpha/issues/89).
> **TDD reference:** §3.2 (Strategy Engine), §5.2.2 (Strategy Engine service descriptor).

## 1. What this is

A soft gate that drops market-data ticks for symbols whose resolved venue is closed at the tick's timestamp. Lives in `StrategyEvaluationService` between the Kafka consumer and the strategy chain — closed-market ticks never reach `strategy.onTick()` or `strategy.evaluate()`.

Two motivations:

1. **Indicator hygiene** — Momentum's EMA / RSI state and any other rolling indicator drift when fed after-hours quotes. A 4 a.m. crossover on an illiquid pre-market print is not a signal.
2. **Multi-region readiness** — Phase 3.1.x ships an IBKR adapter; the same tick stream may carry NASDAQ-listed names alongside future TSE / LSE names. The gate routes by symbol, so the engine can ingest a single multi-market tick stream and still trade each name only when its market is open.

## 2. Behaviour

| Situation | Outcome |
| --- | --- |
| `trading-hours.enabled: false` | Every tick passes through. Pre-3.1.3 behaviour. |
| Tick timestamp falls inside a configured session window for the symbol's market | Tick is delivered to the strategy. |
| Tick timestamp on a non-trading day (weekend) or holiday | Tick is dropped; `mariaalpha_strategy_ticks_suppressed_total{reason="market_closed"}` increments. |
| Tick timestamp outside any session window (incl. lunch break on TSE) | Tick is dropped. |
| Symbol's resolved market is **not** in `markets:` | Tick passes through; a `WARN` log is emitted. Refusing to block on a missing entry is the safer side. |
| Symbol not in `symbol-overrides:` | Falls back to `default-market`. |

The gate uses the **tick's** timestamp, not wall clock — so historical replays (backtest, the CSV-driven simulator) are gated against the date in the data.

## 3. Configuration

```yaml
strategy-engine:
  trading-hours:
    enabled: true
    # Symbols not explicitly overridden resolve to this market.
    default-market: NYSE

    markets:
      NYSE:
        timezone: America/New_York   # IANA tz; handles DST automatically.
        sessions:
          - open: "09:30"             # local time, inclusive.
            close: "16:00"            # local time, exclusive.
        trading-days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
        holidays:                    # ISO-8601 dates in the market's local calendar.
          - 2026-07-03               # Observed Independence Day
      TSE:
        timezone: Asia/Tokyo
        sessions:
          # TSE runs two sessions per day: Zenba (morning) and Goba (afternoon),
          # separated by a one-hour lunch break.
          - open: "09:00"
            close: "11:30"
          - open: "12:30"
            close: "15:30"
        trading-days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]

    symbol-overrides:
      7203: TSE
      SAP:  LSE
```

Notes:

- `open` is inclusive; `close` is exclusive. Matches every exchange API's convention.
- Multiple `sessions` per market handle split-session venues (TSE Zenba/Goba). Order doesn't matter — the gate accepts a hit on any window.
- `holidays` are checked against the **market's** local calendar — so a US holiday for NYSE and a Japanese holiday for TSE are independent lists.
- DST is handled by the IANA timezone — no need to encode summer/winter session shifts.

## 4. Metrics

| Meter | Type | Tags |
| --- | --- | --- |
| `mariaalpha_strategy_ticks_suppressed_total` | Counter | `symbol`, `reason="market_closed"` |

Future reasons can land on the same meter (e.g. data-quality gates, staleness filters).

## 5. Where it lives

- Config — [`strategy-engine/.../sessions/TradingHoursConfig.java`](../../strategy-engine/src/main/java/com/mariaalpha/strategyengine/sessions/TradingHoursConfig.java)
- Service — [`strategy-engine/.../sessions/TradingHoursService.java`](../../strategy-engine/src/main/java/com/mariaalpha/strategyengine/sessions/TradingHoursService.java)
- Integration — `StrategyEvaluationService#evaluate` checks the gate before consulting the regime selector / router ([file](../../strategy-engine/src/main/java/com/mariaalpha/strategyengine/service/StrategyEvaluationService.java))
- Config defaults — `strategy-engine/src/main/resources/application.yml`

## 6. Future work

- **Auction session handling** (3.3.2 in the roadmap) — TSE has an opening-auction (Itayose) and closing-auction window that this gate currently treats as "closed". A `phase: PRE_OPEN | CONTINUOUS | CLOSING_AUCTION` enum on `SessionWindow` would let strategies opt into auction-only orders.
- **Per-symbol calendar overrides** — useful for IPO halts, regulatory suspensions, or single-stock circuit breakers.
- **Auto-discovery from an exchange calendar feed** — replace the static holiday list with a daily refresh from a vendor (NYSE's `NYSEMKT` calendar, JPX's holiday CSV).
