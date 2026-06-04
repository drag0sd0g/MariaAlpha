# Internal Crossing Engine: Concepts and MariaAlpha Implementation

## 1. What Is Internalization?

**Internalization** is the practice of matching a buy order against a sell order that arrives at the same broker-dealer, without sending either to a public exchange. The two orders **cross** internally at a price between the bid and ask — typically the NBBO midpoint — and the broker pockets the bid-ask spread as a "spread capture" instead of paying it to a market venue.

The two economic effects:

1. **Spread capture.** If the NBBO is $99.98 / $100.02, a LIT venue charges the buyer $100.02 and pays the seller $99.98 — a $0.04 spread paid to the venue's queue. Crossing both legs at the midpoint $100.00 leaves the $0.04 inside the firm.
2. **No market impact.** Because the cross happens off-exchange, the public order book is never touched. There's no signaling, no leakage to predatory flow, and no price drift on the parent.

Both sides of the cross get a **price improvement** vs. the lit venue's quote — the buyer pays less, the seller receives more — and the broker captures the residual spread.

This is the canonical "principal-vs-agency" decision a sell-side desk makes for every order:

- **Internalize** when there's offsetting interest already sitting on the desk and the regulator allows it.
- **Route externally** otherwise.

The internal crossing engine is the in-process matching engine behind MariaAlpha's `INTERNAL_CROSS` venue. It gives the Smart Order Router a destination that behaves like a real internalization desk: a per-symbol FIFO book, midpoint-priced crosses, and tick-driven sweep so price-blocked LIMITs eventually fire when the NBBO moves into range.

## 2. The Matching Engine

### State

The engine maintains a per-symbol, per-side FIFO queue of resting orders:

```
book = {
  "AAPL": { BUY:  [r1, r2, r3, ...],
            SELL: [s1, s2, s3, ...] },
  "MSFT": { BUY:  [...],
            SELL: [...] },
  ...
}
```

Every resting order tracks its remaining quantity so the engine can match in slices.

### Matching rules

When an order arrives:

1. Compute the **NBBO midpoint** `mid = (bid + ask) / 2` from `MarketStateTracker`.
2. Walk the opposite-side queue in **FIFO arrival order**.
3. For each resting counterparty, check whether `mid` is acceptable to both:
   - **BUY** accepts `mid ≤ limit_price` (or any `mid` if MARKET).
   - **SELL** accepts `mid ≥ limit_price` (or any `mid` if MARKET).
4. Match `qty = min(aggressor.remaining, resting.remaining)` at price `mid`.
5. Emit a `MidpointCross` event for each match.
6. Decrement both sides; if the resting order is fully consumed, remove it from the book.
7. If the aggressor still has remainder and no more counterparties are available, rest it.

Time priority is enough — there's no price-improvement competition within a single midpoint, so first-arrived-first-matched is fair and predictable.

### Limit-price gating

The gate prevents an order from crossing at a worse price than its operator agreed to pay:

```
BUY  limit 100.00 vs. midpoint 100.02 → no cross (BUY would overpay).
SELL limit 100.00 vs. midpoint 100.02 → cross (SELL gets above-limit price).
BUY  limit 100.02 vs. midpoint 100.00 → cross (BUY pays below limit).
```

MARKET orders accept any midpoint. STOP orders aren't routed to `INTERNAL_CROSS` — they're triggered upstream and reroute as MARKET.

### Re-evaluation on NBBO updates

The engine isn't a passive book — when the NBBO shifts, previously price-blocked orders may become crossable. The adapter's scheduled `tickIntervalMs` sweep calls `engine.sweep()` which re-walks every symbol's book at the current midpoint and emits any newly-matchable crosses.

This handles the realistic case where two LIMIT orders straddling the bid/ask both rest, then the market moves into the range where they meet.

## 3. Simulated Counterparty (Optional)

A real internalization desk needs real two-way interest. In a simulator with one or two strategies feeding the venue, that two-way interest often doesn't appear naturally. To keep the simulated venue useful for demos and load testing, the adapter retains the two probability knobs from the original 2.1.2 stub, **reinterpreted**:

- `cross-probability-on-submit` — probability that an order which finds no real counterparty on submit gets a synthetic counterparty injected after `fillLatencyMs`. Conceptually: "how likely is hidden internal interest to appear in the next millisecond."
- `match-probability-per-tick` — probability that during the periodic sweep, an existing resting order (per symbol) gets a synthetic counterparty injected. Conceptually: "how likely is hidden internal interest to materialize over the next `tickIntervalMs`."

Both default to `0` in the integration test config so the assertions can pin on real matching. In `application-simulated.yml` they default to a non-zero rate so manual smoke tests against `docker-compose` produce visible crosses with a single strategy.

**Real matching is always tried first.** Synthetic counterparties are only conjured when the book genuinely has no opposite-side interest. The synthetic vs. real distinction is preserved in `MidpointCross.synthetic` and on the `mariaalpha.execution.internal.crosses.total` counter's `synthetic` tag, so dashboards can separate them.

## 4. The Adapter Boundary

```mermaid
flowchart LR
    OES[OrderExecutionService] -->|submitOrder(instr)| ADP[SimulatedInternalCrossingAdapter]
    ADP -->|submit(order)| ENG[InternalCrossingEngine]
    ENG -->|MidpointCross| ADP
    ADP -->|ExecutionReport (async)| OES
    OES -->|lifecycle transition| LM[OrderLifecycleManager]
    LM -->|orders.lifecycle| KAFKA[(Kafka)]
```

Two design choices worth calling out:

1. **The adapter dispatches `ExecutionReport` callbacks asynchronously** through its own scheduler. This matches the LIT/DARK adapter contract and gives the caller of `submitOrder` time to transition the order to `SUBMITTED` before the `FILLED` callback arrives. A synchronous publication would race the `NEW → SUBMITTED` transition and fail the state-machine guard.
2. **The engine fans out to multiple listeners** — the adapter (for the execution-report path) and `InternalCrossingMetrics` (for Prometheus). The adapter is the only one whose callback feeds back into `OrderExecutionService`; the metrics listener is purely observational.

## 5. Spread Capture Math

Spread capture per cross:

```
spread_bps = (ask − bid) / midpoint × 10_000
captured  = spread_bps / 10_000 × midpoint × quantity
          = (ask − bid) × quantity
```

A 100-share cross of NVDA at midpoint $875.80 with a $0.04 NBBO spread captures `0.04 × 100 = $4.00`. The engine sums this across all crosses into `CrossingStats.spreadCapturedNotional`, surfaced via `GET /api/execution/internal-crossing/stats`.

## 6. REST Surface

| Endpoint | Returns |
| --- | --- |
| `GET /api/execution/internal-crossing/stats` | `CrossingStats` — counters + resting depth + cumulative spread captured |
| `GET /api/execution/internal-crossing/book` | Map of symbol → `BookSide(buyQty, buyOrders, sellQty, sellOrders)` |
| `GET /api/execution/internal-crossing/recent` | Last 256 `MidpointCross` events, newest first |

All three are routed through the API Gateway (`/api/execution/**`) and require the `X-API-Key` header.

## 7. Metrics

| Meter | Type | Tags | Purpose |
| --- | --- | --- | --- |
| `mariaalpha.execution.internal.crosses.total` | Counter | `symbol`, `synthetic` | Internalization rate per symbol, with real-vs-synthetic split |
| `mariaalpha.execution.internal.shares.crossed.total` | Counter | `symbol` | Volume internalized |
| `mariaalpha.execution.internal.spread.captured.bps` | Distribution Summary | `symbol` | Spread-capture quality per cross |
| `mariaalpha.execution.internal.book.buy.depth` | Gauge | — | Live buy-side queue depth |
| `mariaalpha.execution.internal.book.sell.depth` | Gauge | — | Live sell-side queue depth |
| `mariaalpha.execution.internal.book.resting.orders` | Gauge | — | Total resting orders across all symbols/sides |

The *Post-Trade & Quality* dashboard's "internalization rate" panel divides `mariaalpha_execution_internal_crosses_total{synthetic="false"}` by `mariaalpha_execution_venue_fills_total{venue_type="LIT"}` to show what fraction of flow stayed on-house.

## 8. Configuration

```yaml
# application.yml — defaults
execution-engine:
  internal-crossing:
    venue: INTERNAL_CROSS
    cross-probability-on-submit: 0.6   # synthetic on-submit liquidity rate
    tick-interval-ms: 50               # sweep cadence
    match-probability-per-tick: 0.30   # synthetic per-tick liquidity rate
    fill-latency-ms: 1                 # dispatch delay so SUBMITTED wins the CAS
    max-pending: 1000                  # capacity gate; rejects beyond this
    seed: -1                           # -1 = unseeded production RNG
```

Set both probability knobs to `0.0` for tests that require deterministic real matching only (see `InternalCrossingIntegrationTest`).

## 9. Trade-offs and What Was Deliberately Left Out

- **Single global lock.** Submit / cancel / sweep all serialize on one `synchronized(lock)`. Internalization volumes in this simulator are tiny vs. the throughput where a single-writer Disruptor ring would pay off. The TDD's *Other Considerations* section ([§5.3.7 → LMAX Disruptor refactor](../technical-design-document.md#lmax-disruptor-refactor-for-in-process-hot-paths)) flags this as a natural pilot site for a Disruptor-based refactor.
- **No price-time priority across symbols.** Each symbol has its own FIFO; we don't try to match A-symbol BUY against B-symbol SELL. Cross-symbol matching is its own engine and belongs separately.
- **No per-order tag-based segregation.** A real desk would refuse to cross firm orders against client orders, or refuse to cross orders tagged "no internalize." The simulator doesn't carry that metadata yet; when 2.5.1 (RFQ + client tiering) lands, the engine will need a `crossingEligibility` predicate.
- **Synthetic counterparties are accounting fictions.** They appear as `MidpointCross.synthetic = true` and are excluded from the "real internalization rate" panel. They keep the dashboard alive in single-strategy demos but never inflate the headline KPI.
