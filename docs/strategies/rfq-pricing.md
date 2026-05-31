# RFQ Pricing Engine

> **Issues:** [2.4.1 — Inventory-aware RFQ pricing](https://github.com/drag0sd0g/MariaAlpha/issues/71), [2.4.2 — Volatility + ADV-relative pricing](https://github.com/drag0sd0g/MariaAlpha/issues/72).
> **Spec:** FR-40 (TDD §3.8) and the §2.6.2 sequence diagram.

## 1. What this is

The **Request for Quote** (RFQ) workflow models the manual / voice-equivalent leg of a sell-side desk's day: a client (or, in our MVP, a trader sitting at the React UI) asks "what would you bid me and offer me for *N* shares of *X*, right now?" and the desk produces a two-way price that reflects:

- **Where the market is** — the live NBBO mid we already observe on `market-data.ticks`.
- **How the order would land on our book** — long inventory means we want to *offload*, so we make ourselves look cheap to buyers and stingy to sellers (skew the mid down). Short inventory mirrors.
- **How risky the next 10 seconds are** — high realised volatility means a wider spread, because we'd rather walk away from the trade than be picked off on a 1-σ jump.
- **How hard the order is to source / unwind** — a 600 k-share clip on a 60 M-ADV stock is 1 % of the day's volume and *will* move the market; we widen accordingly.

Unlike VWAP / TWAP / IS / POV / Close — which all answer "how do I work this parent over time?" — RFQ answers "what price am I willing to risk-transfer the whole clip at, right now?" It is therefore conceptually different from the other strategies and **does not** implement `TradingStrategy`; it lives as its own pricing engine and surfaces through dedicated REST endpoints.

On accept, the engine emits an `OrderSignal` with `strategyName="RFQ"` so the execution pipeline (risk checks, SOR, venues) is none the wiser — the rest of the system sees a normal LIMIT order.

---

## 2. The pricing model

The engine composes the quote as a sequence of additive bps adjustments around the market mid:

```
mid                       = bookSnapshot.mid                            ← MarketStateCache
inventoryNotional         = position.netQuantity × mid                  ← PositionLookup
inventorySkewFraction     = λ × (inventoryNotional / neutralNotional)
inventorySkewBps          = clamp(inventorySkewFraction × 10_000,
                                  -maxSkewBps, +maxSkewBps)             ← issue 2.4.1
adjustedMid               = mid × (1 − inventorySkewBps / 10_000)

realizedVolBps            = sample-stdev(log returns over rolling window) × 10_000
volWideningBps            = volScalar × realizedVolBps                   ← issue 2.4.2

advFraction               = quantity / ADV(symbol)
advWideningBps            = advScalar × advFraction × 10_000             ← issue 2.4.2

halfSpreadBps             = baseSpreadBps / 2 + volWideningBps + advWideningBps
bid                       = adjustedMid × (1 − halfSpreadBps / 10_000)
ask                       = adjustedMid × (1 + halfSpreadBps / 10_000)
```

### 2.1 Inventory skew (2.4.1)

The skew is applied to the **mid**, not to the half-spread, because we want both bid *and* ask to slide together when the desk wants to flatten. Concretely:

- Long inventory (positive `netQuantity`) → positive `inventorySkewBps` → `adjustedMid < mid`. Both our buy price (bid) and our sell price (ask) move down. The lower bid makes it cheaper for us to add even more inventory if a seller hits — but we accept that risk because the lower ask also makes us *attractive to a buyer who wants to lift us out of our position*. Net effect: directional pressure to flatten.
- Short inventory mirrors: `adjustedMid > mid`; we offer aggressively cheap to a seller who can flatten our short.
- Capped at `inventoryMaxSkewBps` so a stale or runaway position can't quote arbitrarily far from market.

### 2.2 Volatility widening (2.4.2)

Realised vol is a rolling sample standard deviation of mid-price log returns, expressed in bps:

```
returns_i = ln(mid_i / mid_{i-1})
vol_bps   = stdev(returns) × 10_000
```

Computed in `VolatilityTracker`. The window length is `volatility-window-size` ticks (default 30). The widening is purely additive to the half-spread on **both** sides — the engine does not currently use directional vol-skew (that's a Phase-4 extension).

### 2.3 ADV-relative widening (2.4.2)

`advFraction = quantity / ADV(symbol)`. The half-spread widens by `advScalar × advFraction × 10_000` bps. A 100-share clip on AAPL (`ADV = 60 M`) widens by `0.30 × (100/60_000_000) × 10_000 ≈ 0.005 bps` — essentially free. A 600 k-share clip on the same name widens by ~30 bps. For unmapped symbols `ADV = 0` and the term is suppressed (so the engine still returns a quote, just without size-relative widening).

### 2.4 Quote validity

Quotes are issued with a TTL (`quote-validity-ms`, default 10 s) and stored in `RfqQuoteStore`. The `/accept` endpoint:

1. Looks up the `quoteId`; returns **410 Gone** if unknown or expired (evicted on read).
2. Validates the supplied `price` matches the quoted bid (SELL) or ask (BUY) within a 0.01 tolerance; returns **409 Conflict** otherwise.
3. Constructs an `OrderSignal` and publishes to `strategy.signals`.

The tolerance exists because the JSON round-trip can introduce harmless trailing-decimal artefacts; it is *not* a mechanism for last-look re-pricing.

---

## 3. End-to-end example

A trader asks for a quote on 500 MSFT while the desk is already long 200 MSFT from a prior fill:

```
POST /api/rfq/quote     {"symbol":"MSFT","quantity":500}
```

Response (formatted):

```json
{
  "quoteId":  "0ee4acfe-…",
  "symbol":   "MSFT",
  "quantity": 500,
  "marketMid":   415.35,
  "adjustedMid": 414.104,
  "bid":         413.9612,
  "ask":         414.2467,
  "breakdown": {
    "inventoryNetQuantity":  200.0,
    "inventoryNotionalUsd":  83070.0,
    "inventorySkewBps":      30.0,
    "realizedVolBps":         2.7745,
    "volWideningBps":         1.3873,
    "advParticipationFraction": 2e-05,
    "advWideningBps":         0.06,
    "baseHalfSpreadBps":      2.0,
    "totalHalfSpreadBps":     3.4473,
    "advShares":              25000000
  },
  "issuedAt":  "2026-05-31T17:04:22.452Z",
  "expiresAt": "2026-05-31T17:04:32.452Z",
  "validForMs": 10000
}
```

What just happened:

1. Mid is `415.35`. We're long 200 sh, so inventoryNotional ≈ `$83 k`. Raw skew would be `1.0 × 83k/1M × 10_000 ≈ 830 bps` — but the cap clamps it to **+30 bps**, shifting `adjustedMid` to `414.104`.
2. Realised vol over the rolling window is ~2.77 bps; with `volScalar=0.5` this adds 1.39 bps to each side of the spread.
3. 500 / 25 M ADV is 0.002 % — `advScalar=0.3` adds a token 0.06 bps.
4. Total half-spread is `2 + 1.39 + 0.06 = 3.45` bps, so bid/ask straddle `adjustedMid` by ~14 cents on each side.

The trader accepts the ask (sells MSFT to us is a BUY from our side):

```
POST /api/rfq/accept   {"quoteId":"0ee4acfe-…","side":"BUY","price":414.2467}
→ 200 OK
{
  "quoteId":"0ee4acfe-…","symbol":"MSFT","status":"ACCEPTED",
  "signal": {
    "symbol":"MSFT","side":"BUY","quantity":500,
    "orderType":"LIMIT","limitPrice":414.2467,
    "strategyName":"RFQ","timestamp":"…"
  }
}
```

The signal lands on `strategy.signals`; the Execution Engine runs the standard risk-check chain, the SOR picks a venue (often `INTERNAL_CROSS` for desk-vs-desk flow), and the fill flows back to the Order Manager which updates the position.

---

## 4. Configuration reference

```yaml
strategy-engine:
  rfq:
    base-spread-bps: 4.0                    # 4 bps base spread = 2 bps half on each side
    inventory-lambda: 1.0                   # mid shift per fraction of neutral-notional
    inventory-neutral-notional: 1000000     # "soft" inventory budget in USD
    inventory-max-skew-bps: 30.0            # absolute cap on the mid shift
    vol-scalar: 0.50                        # widen by ½ × realised-vol-bps
    adv-scalar: 0.30                        # widen by 0.30 × (size/ADV in bps)
    quote-validity-ms: 10000                # client has 10 s to accept
    order-manager-base-url: ${ORDER_MANAGER_BASE_URL:http://localhost:8086}
    position-lookup-timeout-ms: 500         # short timeout; degrades to flat on stall
    volatility-window-size: 30              # rolling sample count for VolatilityTracker
    reference-data:
      defaults: { sector: UNKNOWN, beta: 1.0, adv: 0 }
      symbols: [ … see application.yml for the MVP universe … ]
```

### Picking sensible values

| Knob | Tighter | Looser |
|---|---|---|
| `base-spread-bps` | Aggressive market-maker who wants the flow even at slim spreads. | Risk-averse desk that won't get out of bed for less than 5 bps. |
| `inventory-lambda` | Steep skew → aggressive de-risking when long / short. | Patient — let the position run. |
| `inventory-max-skew-bps` | Tight cap → the desk never quotes more than X bps off market. | Loose cap → big positions can quote far off market to *force* a hedge. |
| `vol-scalar` | Volatile names get heavily widened (risk-off). | Volatility doesn't move the spread much. |
| `adv-scalar` | Big orders pay through the nose. | Block-friendly. |
| `volatility-window-size` | Short → spread reacts fast to recent dislocations. | Long → smoother but lagging. |

---

## 5. Operations

### Metrics (Prometheus, scraped from `:8083/actuator/prometheus`)

| Metric | Labels | Notes |
|---|---|---|
| `mariaalpha_rfq_quotes_total` | `symbol` | One per `/quote` call that returned 200. |
| `mariaalpha_rfq_accepts_total` | `symbol`, `side` | One per successful `/accept`. |
| `mariaalpha_rfq_inventory_skew_bps` | `symbol` | Signed; sum / count gives mean over the scrape window. |
| `mariaalpha_rfq_vol_widening_bps` | `symbol` | |
| `mariaalpha_rfq_adv_widening_bps` | `symbol` | |
| `mariaalpha_rfq_total_half_spread_bps` | `symbol` | Useful sanity gauge — should sit near `baseSpreadBps/2` in calm regimes. |

### Failure modes

| Scenario | Effect | Recovery |
|---|---|---|
| No market data for the symbol yet | `/quote` returns **503**. | Engine accepts the symbol once the first tick lands in `MarketStateCache`. |
| Order Manager unreachable | `PositionLookup` returns `unavailable`; engine treats as flat. The breakdown reports `inventoryNetQuantity=0`. | Transient — next call retries. The `MlSignalHealthIndicator` is unaffected; OM dependency is intentionally off the readiness probe. |
| Trader accepts after expiry | `/accept` returns **410 Gone**. | Re-request a fresh quote. |
| Price re-keyed at accept | `/accept` returns **409 Conflict**. | Resubmit with the quoted price. |

### Tuning checklist when a position runs

If a long MSFT position keeps growing despite the inventory skew:

1. Check the `inventory_skew_bps` distribution for `symbol=MSFT` — if it's stuck at the cap, raise `inventory-max-skew-bps`.
2. Check `inventory-lambda` and `inventory-neutral-notional` — a $1 M neutral notional means a $1 M long shifts mid by `λ × 100 % × 10_000 = 10_000` bps before the cap. Lower the neutral or raise λ to make the skew bite sooner.
3. Confirm the OM position is what you think it is — `GET /api/positions/MSFT` on port 8086.

---

## 6. Limits & future work

The MVP intentionally stops here. Real desks layer more in:

| Future extension | Why we punted | Where it would slot in |
|---|---|---|
| Directional vol-skew | Spread asymmetry needs a directional realised-vol estimator, which isn't in `VolatilityTracker`. | `RfqPricingEngine` would split `halfSpreadBps` into `bidHalfBps` / `askHalfBps`. |
| Client tiering | Phase-4 (issue 4.7.1). The accept endpoint already records the signal's source; once a client identity exists upstream the engine can multiply `baseSpreadBps` per client tier. | A per-client multiplier injected before step 3 of §2. |
| Cross-asset / ETF arbitrage hedge | Out of scope for cash equities MVP. | A hedge stage between accept and publish that fans out additional `OrderSignal`s. |
| Inventory aging | Long positions held overnight should skew further. | A timestamp on positions + a time-decay factor applied to `inventoryLambda`. |
| Realised vs. implied vol | We don't subscribe to options data. | Phase-3 derivatives work (issues 3.2.1–3.2.2). |

---

## 7. References

- **TDD:** [`technical-design-document.md`](../technical-design-document.md) — FR-40, §2.6.2 RFQ sequence diagram, §5.4 ERD.
- **Service explainer:** [`strategy-engine.md`](../services/strategy-engine.md) — the RFQ pricing engine in the broader strategy-engine architecture.
- **TCA methodology:** [`../guides/tca-methodology.md`](../guides/tca-methodology.md) — how spread cost from RFQ fills is measured post-trade.
- **Source:** [`strategy-engine/src/main/java/com/mariaalpha/strategyengine/rfq/`](../../strategy-engine/src/main/java/com/mariaalpha/strategyengine/rfq/).
- **Configuration:** [`strategy-engine/src/main/resources/application.yml`](../../strategy-engine/src/main/resources/application.yml) (`strategy-engine.rfq.*`).
- **UI page:** [`ui/src/pages/Rfq.tsx`](../../ui/src/pages/Rfq.tsx).
