# Smart Order Router (SOR) — Explainer

> **Status**: Implemented in Phase 2.1 (issues #53 / 2.1.1 and #54 / 2.1.2).
> **Component**: `execution-engine`.
> **Audience**: traders, ops, platform engineers, on-call.

---

## 1. What is a Smart Order Router?

A **Smart Order Router (SOR)** is the part of an execution-management system that
**decides where to send each child order** when there is more than one place it could go.

For a stock like AAPL, the same name typically trades simultaneously on:

- **Public exchanges** (NYSE, NASDAQ, IEX, …) — every quote is visible, anyone can hit it.
- **Dark pools** (LiquidNet, ITG POSIT, IEX Dark, …) — bids and offers are hidden until they
  match; usually fill at the midpoint of the public best-bid/best-offer (NBBO).
- **Internal crossing engines** — operated by a broker-dealer; cross client orders against
  other client orders or against the firm's own inventory, without going to an exchange.

Each venue offers a different mix of:

| Quality | Trade-off |
|---|---|
| Speed of fill (liquidity, latency) | Lit venues have visible depth; dark/internal have probabilistic fills. |
| Price (fees, midpoint improvement) | Lit makers earn rebates but pay taker fees; dark/internal often fill at midpoint with lower or zero fees. |
| Information leakage | Public quotes signal intent; dark/internal hide it. |

The SOR's job is to make that trade-off **per child order**, automatically and consistently.

### Why does this matter? (Business view)

- **TCA (transaction-cost analysis) improves**: orders go to the venue with the best expected
  cost net of fees, not the first one in a static list.
- **Information leakage shrinks**: large orders can be routed to dark/internal first so the
  broader market doesn't see the intent and front-run.
- **Auditability**: every routing decision is recorded with the inputs it used. Compliance,
  best-execution reports, and post-trade analytics consume the same audit stream.

### Why does this matter? (Technical view)

- Decouples *which venue* from *how to submit* — the same `Order` object is scored, then
  handed to a venue-specific adapter.
- Introduces a pluggable scoring model. New criteria (e.g. an ML-based fill-probability model)
  drop in as a new `VenueScoreCriterion` bean.
- Adds new failure modes (an enabled venue can be *unhealthy*) which need to be reflected in
  routing — the SOR filters by adapter health on every call.

---

## 2. The vocabulary, defined

### 2.1 Lit venues

A **lit venue** publishes both sides of its order book in real time. Bids and offers are
visible to every market participant. Examples: NYSE, NASDAQ, IEX, Cboe.

Properties relevant to routing:

- **Top-of-book size**: how much volume sits at the best bid/offer right now. Used to estimate
  the probability of an immediate fill at the quoted price.
- **Taker fee** (typically a few tenths of a bp to a few bps): paid when you remove liquidity.
- **Maker rebate**: paid *to* you when you provide passive liquidity. Negative effective fee.
- **Information leakage**: high — every quote you post is visible.

In MariaAlpha, lit venues are modelled as `VenueType.LIT`. The MVP ships with two lit venues:
`SIMULATED` (paper-trading harness) and `ALPACA` (real broker).

### 2.2 Dark pools

A **dark pool** is an Alternative Trading System (ATS) that does *not* publish quotes. You
submit an order; it sits in a hidden book; if there's a matching contra-side order, the trade
prints at the midpoint of the public NBBO (sometimes with a small improvement).

Why use one?

- **Hide intent** — a 50,000-share order in a dark pool doesn't tip off the market.
- **Save half the spread** — typical fill at midpoint, so you give up no spread vs. crossing
  the lit book.
- **Lower or zero fees** in many cases.

The trade-off is **certainty of fill**: there's no visible book, so you don't know how much
contra is in the pool. The pool advertises a historical **fill rate** (e.g. 0.40 = 40% of
orders eventually match), and the SOR uses that as a probabilistic input.

Real-world examples: LiquidNet, ITG POSIT, Cboe BIDS, IEX Dark. MariaAlpha simulates one
venue under `DARK_POOL_A`.

### 2.3 Internal crossing engines

An **internal crossing engine** (also called an *internalisation* engine) is operated by a
broker-dealer. When a client order arrives, the broker first tries to cross it against:

1. **Other client orders** on the opposite side ("client-client cross"), or
2. **The firm's own inventory** ("principal cross").

If a cross succeeds, the trade prints internally at the midpoint of the NBBO. No exchange,
no dark pool, no fees beyond what the broker charges.

Why use one?

- **Best price improvement**: zero fees, zero half-spread give-up, zero info leakage.
- **Speed**: a millisecond, vs. the round-trip to an exchange.

Trade-offs:

- Only works if contra-side flow exists. Otherwise the order sits.
- Subject to brokerage best-execution rules (Reg NMS in the US, MiFID II in the EU): the
  broker must demonstrate the internal print is at least as good as the lit market.

In MariaAlpha, `SimulatedInternalCrossingAdapter` delegates to a real `InternalCrossingEngine`
(issue 2.1.10) that maintains a per-symbol FIFO book and matches offsetting BUY/SELL interest at
the NBBO midpoint. When the book has no opposite-side interest, the adapter can optionally inject
a simulated counterparty (controlled by the `cross-probability-on-submit` /
`match-probability-per-tick` knobs) so the venue stays useful in single-strategy demos. Real
systems hook the same engine into RFQ flow, axe lists, and the firm's prop book.

### 2.4 Other terms you'll see

- **NBBO** — National Best Bid and Offer. The tightest public bid+ask across all lit venues.
- **Midpoint** — (bid + ask) / 2. The reference price for most dark/internal prints.
- **Spread** — ask − bid. Half-spread is the cost of crossing the book on a single side.
- **Aggressive vs passive**: an order is *aggressive* if it crosses the spread (market order, or
  a limit that crosses) and *passive* otherwise. Aggressive pays the taker fee; passive can
  earn a maker rebate.
- **ADV** — Average Daily Volume. Used by some scorers (Phase-4 in MariaAlpha) to estimate
  market impact relative to typical daily flow.

---

## 3. How the MariaAlpha SOR makes a decision

### 3.1 Inputs

For each `Order` to be routed, the SOR receives:

- The order itself (`symbol`, `side`, `quantity`, `orderType`, optional `limitPrice` / `stopPrice`).
- The current `MarketState` for the symbol (bid, ask, mid, last-update timestamp), pulled
  in-process from a `MarketStateTracker` that is fed by the market-data Kafka topic.
- The configured set of `Venue`s (from `execution-engine.sor.venues` in YAML).
- The `SorConfig.Weights` (one weight per criterion, summing to 1).
- The set of *healthy* venue adapters, queried from `VenueAdapterRegistry`.

No I/O, no randomness, no clock. Given the same inputs, the SOR always picks the same venue.
This determinism is intentional — it makes routing decisions reproducible for TCA, replay, and
post-trade analytics.

### 3.2 The five scoring criteria

Each `VenueScoreCriterion` returns a `double ∈ [0, 1]`. Higher is better.

| Criterion | Lit | Dark | Internal | Notes |
|---|---|---|---|---|
| **PriceImprovement** | 0 (no improvement on a lit cross) | half-spread bps, capped | half-spread bps, capped | Cap configured by `sor.maxPriceImprovementBps` (default 5 bps). |
| **Liquidity** | `min(1, topOfBookSize/qty) × fillRate` | `fillRate` | `fillRate` | Lit penalises orders larger than the visible book. |
| **Latency** | `1 − avgLatencyMs/maxLatencyMs` | same | same | `maxLatencyMs` default 200 ms. |
| **Fees** | `1 − effectiveBps/maxFeeBps` | same | same | Aggressive pays takerFeeBps; passive earns makerRebateBps (negative effective fee). Clamped to [0,1]. |
| **InformationLeakage** | `1 − leakageScore` | same | same | `leakageScore` is a static per-venue prior. Lit=1.0, dark≈0.2, internal=0.0 by default. |

The weighted sum:

```
weightedScore = Σᵢ weightᵢ × criterionᵢ
```

is computed for every healthy & enabled venue. The venue with the highest `weightedScore`
wins. Ties break by registry insertion order (deterministic).

### 3.3 What gets recorded

For every routing decision, the SOR publishes a JSON record to the Kafka topic
`routing.decisions`, keyed by order ID. The full schema:

```json
{
  "orderId": "uuid",
  "venue": "INTERNAL_CROSS",
  "reason": "Selected INTERNAL_CROSS (INTERNAL) with score 0.7055; 3 candidate venues evaluated",
  "timestamp": "2026-05-15T14:30:00.123Z",
  "symbol": "AAPL",
  "side": "BUY",
  "quantity": 100,
  "orderType": "MARKET",
  "selectedVenueType": "INTERNAL",
  "selectedScore": 0.7055,
  "candidateScores": [
    {
      "venue": "SIMULATED",
      "type": "LIT",
      "weightedScore": 0.4750,
      "criteria": {"PriceImprovement": 0.0, "Liquidity": 1.0, "Latency": 0.75, "Fees": 1.0, "InformationLeakage": 0.0}
    },
    {
      "venue": "DARK_POOL_A",
      "type": "DARK",
      "weightedScore": 0.5760,
      "criteria": {"PriceImprovement": 0.224, "Liquidity": 0.4, "Latency": 0.85, "Fees": 0.9, "InformationLeakage": 0.8}
    },
    {
      "venue": "INTERNAL_CROSS",
      "type": "INTERNAL",
      "weightedScore": 0.7055,
      "criteria": {"PriceImprovement": 0.224, "Liquidity": 0.6, "Latency": 0.995, "Fees": 1.0, "InformationLeakage": 1.0}
    }
  ],
  "weights": {"PriceImprovement": 0.25, "Liquidity": 0.25, "Latency": 0.10, "Fees": 0.15, "InformationLeakage": 0.25},
  "marketSnapshot": {"bid": 178.50, "ask": 178.54, "mid": 178.52, "spreadBps": 2.24}
}
```

The TCA and best-execution dashboards downstream consume this stream. Retention is 3 days
(see `docs/technical-design-document.md` § 5.4 — Kafka topics).

### 3.4 Where it sits in the pipeline

```
SignalConsumer → OrderExecutionService.processOrder
  ↓ trading-halt check
  ↓ lifecycleManager.registerOrder
  ↓ handler.validate
  ↓ riskCheckChain.evaluate
  ↓ router.route(order)            ← THE SOR
  ↓ handler.toExecutionInstruction
  ↓ venueAdapters.get(decision.venue()).submitOrder
```

The SOR is between the risk-check chain and the venue adapter, exactly as the
Tick-to-Trade sequence in TDD § 2.6.1 specifies.

---

## 4. How the venues are implemented

### 4.1 The `VenueAdapter` SPI

`VenueAdapter` is a small interface that every venue (real or simulated) implements:

```java
public interface VenueAdapter {
  String venueName();
  VenueType venueType();
  OrderAck submitOrder(ExecutionInstruction instruction);
  OrderAck cancelOrder(String exchangeOrderId);
  void onExecutionReport(Consumer<ExecutionReport> callback);
  void start();
  void shutdown();
  boolean isHealthy();
}
```

It is **standalone** — not extending `ExchangeAdapter` — to avoid Spring bean-ambiguity when
`PrimaryVenueAdapter` wraps the existing profile-bound `ExchangeAdapter` (Alpaca or
Simulated). The `VenueAdapterRegistry` collects every `VenueAdapter` bean into a name-keyed
map and exposes a `healthyNames()` view for the SOR's runtime filter.

### 4.2 `PrimaryVenueAdapter` — wraps the real exchange

Resolves its venue name from `execution-engine.sor.venues[?(@.adapterBean == 'primary')].name`
(so `SIMULATED` under the simulated profile, `ALPACA` under the alpaca profile). All operations
delegate to the wrapped `ExchangeAdapter`. Lifecycle methods are **no-ops** because the wrapped
adapter already manages its own `@PostConstruct start()`.

### 4.3 `SimulatedDarkPoolAdapter`

A hidden book with a periodic match loop. Behaviour:

- `submitOrder` enqueues the order into an in-memory `pending` map and returns an ack.
- A `ScheduledExecutorService` ticks every `tick-interval-ms` (default 100 ms).
- On each tick, for each pending order: skip if the public spread is below `min-spread-bps`
  (tight markets are unattractive for dark), otherwise fill with probability
  `match-probability-per-tick`. Fills print at the midpoint.
- `partial-fill-ratio` controls fill size (default 0.5 — fills half remaining).
- `max-pending` (default 1000) caps the book.
- Deterministic for tests: pass `seed: 42` in config to seed the `Random`. Production uses
  `seed: -1` for unseeded.

### 4.4 `SimulatedInternalCrossingAdapter`

A thin façade over `InternalCrossingEngine` (issue 2.1.10) — see
[`internal-crossing-engine.md`](internal-crossing-engine.md) for the full
write-up. The engine maintains a per-symbol FIFO book and matches offsetting BUY/SELL interest
at the NBBO midpoint, capturing the spread without market impact. Resting interest is swept on a
`tick-interval-ms` cadence so price-blocked LIMITs cross when the NBBO moves into range.

The two probability knobs originally introduced as the 2.1.2 stub remain as a simulated-liquidity
layer: `cross-probability-on-submit` and `match-probability-per-tick` control how often the
adapter asks the engine to synthesize a counterparty when the book has no real opposite-side
interest. Real matching is always tried first. Synthetic crosses are tagged as such on the
`mariaalpha.execution.internal.crosses.total{synthetic="true"}` counter so dashboards can
exclude them from the headline internalization rate.

Stats and per-symbol book depth are exposed under
`/api/execution/internal-crossing/{stats,book,recent}`.

### 4.5 Health and failover

Each adapter exposes `isHealthy()`. The `VenueAdapterRegistry` aggregates them into a set
that `ScoredSmartOrderRouter` consults on every call: a venue that is *configured-enabled*
but *currently unhealthy* is excluded from scoring for as long as it's down.

Per-venue Spring Boot Actuator `HealthIndicator`s (`darkPool`, `internalCrossing`,
`exchangeAdapter`) light up under `/actuator/health` so on-call can see a stuck scheduler
thread without diving into logs.

When *every* configured venue is enabled-but-unhealthy (or no venues are enabled at all),
the SOR emits a *degraded* `RoutingDecision` whose `reason` records "No enabled+healthy
venues; falling back to first registered" — the order then bounces off the
`NoVenueAdapter` rejection in `OrderExecutionService` and the lifecycle transitions to
`REJECTED`. No silent submit attempts; no retries.

---

## 5. Operating the SOR

### 5.1 Configuration knobs

In `application.yml` under `execution-engine`:

```yaml
sor:
  mode: ${EXECUTION_ENGINE_SOR_MODE:scored}      # 'scored' or 'direct'
  max-latency-ms: 200
  max-fee-bps: 10
  max-price-improvement-bps: 5
  decision-cache-size: 1000
  weights:
    price-improvement: 0.25
    liquidity: 0.25
    latency: 0.10
    fees: 0.15
    information-leakage: 0.25
  venues:
    - name: PRIMARY
      type: LIT
      ...
      enabled: true
dark-pool:
  venue: DARK_POOL_A
  tick-interval-ms: 100
  match-probability-per-tick: 0.10
  ...
internal-crossing:
  venue: INTERNAL_CROSS
  cross-probability-on-submit: 0.6
  tick-interval-ms: 50
  ...
```

Weights must sum to 1.0 (validated at startup by `VenueRegistry.validate()`; a
misconfigured app refuses to boot).

### 5.2 REST endpoints (exposed via api-gateway)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/routing/venues` | List configured venues with their scoring inputs. |
| `POST` | `/api/routing/score` | What-if scoring for a hypothetical order — returns the per-criterion breakdown without submitting. |
| `GET` | `/api/routing/decisions/{orderId}` | Last cached routing decision for an order (404 if not in the in-memory ring of `decision-cache-size`). |
| `GET` | `/api/routing/venues/{name}/health` | Per-venue adapter health (`UP`/`DOWN`). |

All are documented via springdoc; OpenAPI JSON at
`http://localhost:8084/v3/api-docs` and Swagger UI at `http://localhost:8084/swagger-ui`.

### 5.3 Metrics (Prometheus)

| Metric | Type | Labels |
|---|---|---|
| `mariaalpha_execution_sor_routing_total` | Counter | `venue`, `venue_type` |
| `mariaalpha_execution_sor_scoring_duration_ms` | Timer + histogram | none |
| `mariaalpha_execution_sor_candidate_score` | DistributionSummary | `venue`, `venue_type` |
| `mariaalpha_execution_venue_submit_total` | Counter | `venue`, `venue_type` |
| `mariaalpha_execution_venue_fills_total` | Counter | `venue`, `venue_type` |

The TDD § 8.3 "internalization rate" panel for Dashboard 3 is
`fills{venue_type="INTERNAL"} / fills{*}`.

### 5.4 One-flag rollback

If the scored router ever misbehaves, flip `EXECUTION_ENGINE_SOR_MODE=direct` in the
environment and redeploy. The `DirectRouter` activates (`@ConditionalOnProperty
mode=direct`) and emits a legacy-shape `RoutingDecision` with venue = `PRIMARY`,
preserving downstream invariants. No data migration needed.

---

## 6. Determinism, randomness, and a caveat about tests

The SOR's *scoring* is deterministic. Given identical inputs (order parameters, market state,
config), every venue gets the same score and the same one wins. That means a burst of 50
identical AAPL market orders, under the default weights and the simulated profile, will all
route to the same venue (currently `INTERNAL_CROSS`, which dominates on info-leakage and
fees while taking only a small hit on liquidity).

This is by design and the right behaviour: routing decisions should be reproducible. To see
diversity in venue selection, you need to vary the inputs:

- **Order quantity** — large orders push lit liquidity below 1 and let dark/internal
  catch up. Try `quantity: 50000` vs `quantity: 100`.
- **Order type / aggression** — passive limits earn rebates from lit venues, changing the
  fees term materially.
- **Weights** — bumping `liquidity` to 0.7 favours lit venues for small orders;
  bumping `information-leakage` to 0.5 favours dark/internal for everything.

The **fill** side of the dark/internal adapters *is* randomised (probabilistic match per
tick), but the seed is configurable for reproducible tests. Tests pass `seed: 42`; production
uses `seed: -1` for an unseeded `Random`.

---

## 7. Verifying the SOR end-to-end

A quick smoke after `just run`:

```bash
# 1. Verify configured venues
curl -s -H "X-API-Key: $MARIAALPHA_API_KEY" \
  http://localhost:8080/api/routing/venues | jq

# 2. What-if score a hypothetical order
curl -s -H "X-API-Key: $MARIAALPHA_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"symbol":"AAPL","side":"BUY","orderType":"MARKET","quantity":100}' \
  http://localhost:8080/api/routing/score | jq

# 3. Submit a real order and inspect the audit
curl -s -X POST -H "X-API-Key: $MARIAALPHA_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"symbol":"AAPL","side":"BUY","orderType":"MARKET","quantity":100}' \
  http://localhost:8080/api/execution/orders

docker exec mariaalpha-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic routing.decisions --from-beginning --max-messages 1 | jq

# 4. Per-venue health
curl -s -H "X-API-Key: $MARIAALPHA_API_KEY" \
  http://localhost:8080/api/routing/venues/SIMULATED/health | jq
curl -s -H "X-API-Key: $MARIAALPHA_API_KEY" \
  http://localhost:8080/api/routing/venues/DARK_POOL_A/health | jq
curl -s -H "X-API-Key: $MARIAALPHA_API_KEY" \
  http://localhost:8080/api/routing/venues/INTERNAL_CROSS/health | jq

# 5. Metrics
curl -s http://localhost:8085/actuator/prometheus | grep -E "sor_|venue_"
```

Automated coverage of the same paths:

- `ScoredRouterKafkaIntegrationTest` — asserts the full audit JSON shape end-to-end through
  a Testcontainers Kafka.
- `MultiVenueRoutingIntegrationTest` — boots the full simulated profile, bursts 50 orders,
  asserts every decision evaluates all 3 venues and is published with a configured-set venue.
- Per-class unit tests for every scorer, the router, and each adapter give > 80% line coverage.

---

## 8. What's deferred to future phases

| Concern | Issue | Why deferred |
|---|---|---|
| Real client-axe matching for internal crossing | 2.4.1 / 2.6 | Needs RFQ + axe-list infrastructure (Phase 2 endgame). |
| Real dark-pool integrations (LiquidNet, ITG POSIT, etc.) | Phase 4 | Vendor onboarding, FIX certifications. |
| Cross-venue parent-order slicing | 2.1.5 – 2.1.9 | That's an algo concern (TWAP/POV/IS), not a router concern. |
| ML-based adaptive scoring | 4.8.1 | Needs feature pipeline + offline training (Phase 4). |
| Persistent venue queues across restart | 3.x | Recovery from crash mid-fill is a Phase-3 concern (orders.lifecycle replay + reconciliation). |
| ADV-relative leakage adjustment | 2.2.3 | Coupled to the ADV risk check; same data dependency. |
| Failover on adapter rejection | 3.1.x | Re-routing the same order risks double-submission; needs idempotency keys at the venue. |

---

## 9. Cross-references

- TDD § 5.3.5 — Smart Order Router (architecture summary).
- TDD § 8.2 — metrics naming convention.
- TDD § 8.3 — Dashboard 3 (internalisation rate).
- `local/plan-2.1.1-smart-order-router.md` — original implementation plan, including the full
  scoring tables and per-criterion math.
- `execution-engine/src/main/java/com/mariaalpha/executionengine/router/` — source.
- `execution-engine/src/main/java/com/mariaalpha/executionengine/adapter/` — venue adapters.
