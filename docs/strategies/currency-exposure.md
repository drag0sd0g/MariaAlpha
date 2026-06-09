# Currency Exposure Tracking

> **Roadmap:** [3.5.3 — Currency exposure tracking](https://github.com/drag0sd0g/MariaAlpha/issues/103).
> **TDD reference:** §3.5 (Order Manager and Position Tracking), §5.2 (Order Manager service).

## 1. What this is

A read-side aggregation that groups open-position exposure (and realized / unrealized P&L) by ISO-4217 currency. A desk trading mixed-currency books gets one row per currency rather than a single base-converted total — so JPY exposure can be evaluated separately from USD without baking in a choice of FX rate.

Exposed via `GET /api/portfolio/currency-exposure` on the API Gateway (routed to the order-manager). The MariaAlpha simulator universe is USD-only, so the live response has a single row in default config; the mapping ships pre-wired for the symbols a Phase 3 IBKR adapter or TSE rollout would add.

## 2. Behaviour

| Situation | Outcome |
| --- | --- |
| No positions at all | `rows: []`, `openPositions: 0` |
| Symbol present in `overrides` | Routed to the override's currency |
| Symbol not in `overrides` | Falls back to `default-currency` |
| Position is flat (`netQuantity == 0`) but has realized P&L | Contributes to `realizedPnl` row but **not** to `grossExposure` / `netExposure` / `positionCount` |
| Position has `lastMarkPrice` set | Exposure marks against `lastMarkPrice` |
| Position has no mark | Exposure marks against `avgEntryPrice` (same fallback as `/api/portfolio/summary`) |
| Short position | Contributes negative `netExposure`, positive `grossExposure` |

Per-row aggregates:

- `grossExposure` — Σ |netQty × mark| across symbols in this currency
- `netExposure` — Σ (netQty × mark), signed (longs minus shorts)
- `realizedPnl` / `unrealizedPnl` — column sums from the position table
- `totalPnl` = realized + unrealized
- `positionCount` — count of non-flat positions in this currency

Rows are sorted alphabetically by currency code for stable UI ordering. **No FX conversion is performed** — every column is in the row's native currency.

## 3. Configuration

```yaml
order-manager:
  currency:
    # Currency assigned to any symbol not listed under overrides. Must be ISO-4217.
    default-currency: USD

    # Universe of currencies the desk expects to see. Used by tooling / future health
    # checks; not enforced at request time (an unexpected currency from overrides will
    # still surface — better to show it than silently drop).
    known: [USD, EUR, JPY, GBP]

    # Symbol → currency overrides. Keys and values are normalised to uppercase. Empty
    # values are dropped (fall back to default).
    overrides:
      7203: JPY  # Toyota on TSE
      SAP:  EUR  # SAP SE on Xetra
```

## 4. API

```http
GET /api/portfolio/currency-exposure
```

Response:

```json
{
  "rows": [
    {
      "currency": "EUR",
      "positionCount": 1,
      "grossExposure": 6000.0000,
      "netExposure": 6000.0000,
      "realizedPnl": 0.0000,
      "unrealizedPnl": 0.0000,
      "totalPnl": 0.0000
    },
    {
      "currency": "USD",
      "positionCount": 2,
      "grossExposure": 15000.0000,
      "netExposure": 15000.0000,
      "realizedPnl": 250.0000,
      "unrealizedPnl": 120.0000,
      "totalPnl": 370.0000
    }
  ],
  "openPositions": 3,
  "asOf": "2026-06-09T05:42:11.034Z"
}
```

## 5. Where it lives

- Config — [`order-manager/.../config/CurrencyConfig.java`](../../order-manager/src/main/java/com/mariaalpha/ordermanager/config/CurrencyConfig.java)
- Aggregator — [`order-manager/.../service/CurrencyExposureService.java`](../../order-manager/src/main/java/com/mariaalpha/ordermanager/service/CurrencyExposureService.java)
- DTO — [`order-manager/.../controller/dto/CurrencyExposureResponse.java`](../../order-manager/src/main/java/com/mariaalpha/ordermanager/controller/dto/CurrencyExposureResponse.java)
- Endpoint — `PortfolioController#currencyExposure` ([file](../../order-manager/src/main/java/com/mariaalpha/ordermanager/controller/PortfolioController.java))
- Config defaults — `order-manager/src/main/resources/application.yml`

## 6. Future work

- **FX conversion to portfolio base** — adds a rates map and an opt-in `?base=USD` query param. Deliberately deferred because it forces a decision about rate-feed cadence (mid vs. bid/ask, intraday vs. EOD) that's only useful once a non-USD position actually exists in prod.
- **Per-currency limits** — `correlated-positions`–style caps once FX-aware risk policies are in scope.
- **Persistence + history** — current implementation is computed on-the-fly. A snapshot table (like `portfolio_snapshots`) becomes attractive once the UI wants a time series.
