# Strategy Engine — How It Works

## Overview

The Strategy Engine is the Java 21 / Spring Boot 3 microservice that sits **between** the Market Data Gateway (upstream, via Kafka) and the Execution Engine (downstream, via Kafka). It is the brain that turns ticks into trading intent. Three jobs live here:

1. **Run execution strategies** — VWAP, TWAP, Momentum, Implementation Shortfall, POV, Close — through a pluggable `TradingStrategy` interface and a `StrategyRegistry`. Each strategy decides whether the current tick warrants emitting an order, and the engine publishes the resulting `OrderSignal` to `strategy.signals` on Kafka.
2. **Confirm or veto signals against the ML model** — the `MlSignalGate` calls the ML Signal Service over gRPC for every signal a strategy produces and applies the FR-12 / TDD §5.2.2 policy: high-confidence agreement confirms (and optionally scales) the trade, high-confidence contradiction suppresses it, low confidence is a pass-through.
3. **Price two-way RFQ quotes** — the `RfqPricingEngine` builds an inventory-aware, volatility-adjusted, size-relative-to-ADV two-way quote on demand from the React UI, and on accept publishes an `OrderSignal` with `strategyName="RFQ"` so the same execution pipeline picks it up.

The service has two HTTP interfaces and one Kafka consumer + producer pair:

| Interface | Port | Protocol | Purpose |
|---|---|---|---|
| REST API | 8082 | HTTP/1.1 + JSON | `/api/strategies/**`, `/api/rfq/**` |
| Actuator | 8083 | HTTP/1.1 + JSON | `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` |
| Kafka consumer | — | — | `market-data.ticks` (group `strategy-engine`, earliest) |
| Kafka producer | — | — | `strategy.signals` (key=symbol) |
| gRPC client | — | HTTP/2 | `SignalService.GetSignal(symbol)` on ML Signal Service :50051 |
| HTTP client | — | HTTP/1.1 + JSON | `GET /api/positions/{symbol}` on Order Manager :8086 |

---

## Architecture

```
                ┌──────────────┐
                │    Kafka     │
                │  (inbound)   │
                └──────┬───────┘
                       │
                market-data.ticks
                       │
                       ▼
                ┌──────────────┐
                │ TickConsumer │  @KafkaListener, one thread per partition
                └──────┬───────┘
                       │
          ┌────────────┴────────────┐
          │                         │
          ▼                         ▼
  MarketStateCache         StrategyEvaluationService
   (per-symbol bid/ask/    │
    mid + rolling window   │ 1. router.getActiveStrategy(symbol)
    for VolatilityTracker) │ 2. strategy.onTick(tick)
                           │ 3. strategy.evaluate(symbol) ─► OrderSignal?
                           │ 4. mlClient.getSignal(symbol) ─► MlSignalResult? [gRPC]
                           │ 5. mlGate.decide(signal, mlResult)
                           │     ├── CONFIRMED  (maybe scaled qty)
                           │     ├── LOW_CONFIDENCE / NO_ML / NEUTRAL_PASS (pass-through)
                           │     ├── OVERRIDDEN (PERMISSIVE / OFF mode)
                           │     └── VETOED     (suppress, do not publish)
                           │ 6. signalPublisher.publish(orderSignal)
                           │
                           ▼
                strategy.signals (Kafka, key=symbol)
                           │
                           ▼
                    Execution Engine

                ┌────────────────────────────────────────┐
                │           RFQ pricing path             │
                ├────────────────────────────────────────┤
   POST /api/rfq/quote ─►  RfqController                 │
                            │                            │
                            ▼                            │
                 RfqPricingEngine.quote(symbol, qty)     │
                            │                            │
       ┌────────────────────┼────────────────────────┐   │
       ▼                    ▼                        ▼   │
 MarketStateCache  PositionLookup           VolatilityTracker
 (current mid)     (Order Manager HTTP)     (rolling stdev)
       │                    │                        │
       │            RfqSymbolReferenceData ────────┐  │
       │            (ADV per symbol)              │   │
       └──────────────────► RfqQuote (bid/ask/    │   │
                            breakdown)            │   │
                            │ stored in RfqQuoteStore
                            │
   POST /api/rfq/accept ───►│  (validate id, price; publish OrderSignal)
                            └─► strategy.signals (same topic, strategyName="RFQ")
```

---

## Strategy Registry & Routing

### `TradingStrategy` (one interface, six implementations)

```java
public interface TradingStrategy {
  String name();
  void onTick(MarketTick tick);
  Optional<OrderSignal> evaluate(String symbol);
  Map<String, Object> getParameters();
  void updateParameters(Map<String, Object> params);
}
```

The Spring container collects every `@Component` implementing the interface into `StrategyRegistry`. New algorithms register simply by being on the classpath — no central enum, no factory switch, no engine-side wiring. The full set today:

| Strategy | Class | Explainer |
|---|---|---|
| VWAP | `strategy.vwap.VwapStrategy` | [`vwap.md`](../strategies/vwap.md) |
| TWAP | `strategy.twap.TwapStrategy` | [`twap.md`](../strategies/twap.md) |
| Momentum / trend-following | `strategy.momentum.MomentumStrategy` | [`momentum.md`](../strategies/momentum.md) |
| Implementation Shortfall (IS) | `strategy.shortfall.ImplementationShortfallStrategy` | [`implementation-shortfall.md`](../strategies/implementation-shortfall.md) |
| POV (Percentage of Volume) | `strategy.pov.PovStrategy` | [`pov.md`](../strategies/pov.md) |
| Close (working-into-the-close + MOC) | `strategy.close.CloseStrategy` | [`close.md`](../strategies/close.md) |

### `SymbolStrategyRouter`

Per-symbol binding lives in `SymbolStrategyRouter` (`ConcurrentHashMap<symbol, TradingStrategy>`). Without an active binding a symbol's ticks are silently ignored — this is intentional so an operator can flip strategies on and off at runtime via the REST API without booting the whole stack:

```
PUT /api/strategies/AAPL          {"strategyName":"VWAP"}    -> bind
PUT /api/strategies/VWAP/parameters {...}                    -> tune strategy params
GET /api/strategies               -> list registered strategies
GET /api/strategies/{symbol}/active -> what's bound right now
```

Note the two namespaces: bindings are keyed by **symbol** (`AAPL`), parameters are keyed by **strategy name** (`VWAP`). The same VWAP instance handles every symbol bound to it.

---

## Tick Consumer & Evaluation

`TickConsumer.onTick` is the single Kafka entry-point. It does two things in lock-step:

1. **Feed `MarketStateCache`** — every tick refreshes the per-symbol bid/ask/last/mid and pushes a new sample onto the rolling mid-price ring. This is what the RFQ engine and the volatility tracker read.
2. **Hand off to `StrategyEvaluationService`** — drives the strategy-evaluation-then-ML-gate-then-publish pipeline.

`StrategyEvaluationService.evaluate(tick)`:

```java
var strategy = router.getActiveStrategy(tick.symbol()).orElseThrow();
strategy.onTick(tick);
var signal = strategy.evaluate(tick.symbol());     // Optional<OrderSignal>
if (signal.isEmpty()) return;

var ml = mlClient.getSignal(tick.symbol());        // Optional<MlSignalResult>
var decision = mlGate.decide(signal.get(), ml);
metrics.recordMlDecision(decision.outcome(), strategy.name(), signal.get().side());

decision.signal().ifPresent(s -> {
  signalPublisher.publish(s);
  metrics.recordSignal(strategy.name(), s.side());
});
```

The split between `evaluate(tick)` (push, per tick) and the strategy's internal state (the Time Bins for VWAP, slice schedule for TWAP, EMA + RSI for Momentum, etc.) means the engine itself is stateless. State lives in the strategy bean. Two consequences:

- Strategy switching is safe at any moment — the new binding takes effect on the next tick.
- Strategies are intentionally **not** sharded by symbol. The same TWAP instance schedules every TWAP-bound symbol; per-symbol state lives in its internal `Map<symbol, TwapSlice>`. This works because each strategy is invoked from one consumer thread per partition and ticks for the same symbol always land on the same partition (Kafka key=symbol).

---

## ML Signal Gate

### The policy

Per FR-12 / TDD §5.2.2, every strategy signal is run past the ML Signal Service. The default policy is:

| ML state | Outcome | Behaviour |
|---|---|---|
| No result (timeout, breaker open, ML unavailable) | `NO_ML` | Publish the strategy signal as-is. |
| Confidence ≤ `confidence-threshold` (default 0.7) | `LOW_CONFIDENCE` | Publish as-is — ML is not informative enough to override. |
| Confidence > threshold and direction agrees | `CONFIRMED` | Publish, optionally with quantity scaled by ML `recommendedSize` (see Sizing below). |
| Confidence > threshold and direction contradicts | `VETOED` *(STRICT mode)* | **Suppress.** No publish. |
| Confidence > threshold and direction contradicts | `OVERRIDDEN` *(PERMISSIVE / OFF mode)* | Publish anyway, but tag the metric. |
| Confidence > threshold and direction is NEUTRAL | `NEUTRAL_PASS` (default) or `VETOED` (if `neutral-suppress=true`) | Configurable — "no opinion" vs "informed flat". |

### Configurable modes

```yaml
strategy-engine:
  ml:
    host: ml-signal-service
    port: 50051
    confidence-threshold: 0.7         # FR-12 spec floor
    veto-mode: STRICT                 # STRICT | PERMISSIVE | OFF
    neutral-suppress: false           # treat NEUTRAL@high-conf as a veto
    sizing-mode: NONE                 # NONE | SCALED
    sizing-neutral-fraction: 0.20     # ML recommendedSize that maps to scale=1.0
    sizing-lower-bound: 0.50          # clamp for the multiplier
    sizing-upper-bound: 1.50
```

`STRICT` is the default — high-confidence contradictions become suppressions. `PERMISSIVE` is useful for shadow-mode rollouts of a new model: the gate still records what *would* have been vetoed (look for `outcome="OVERRIDDEN"`) while the signal flows downstream. `OFF` disables the gate entirely.

### Quantity scaling (the "adjusting urgency" half of FR-12)

When `sizing-mode=SCALED` and the ML side agrees with high confidence, the strategy's quantity is multiplied by:

```
scale = clamp(recommendedSize / sizingNeutralFraction, sizingLowerBound, sizingUpperBound)
```

So a model that's bullish at `recommendedSize=0.30` with the neutral fraction at `0.20` produces a 1.5× clip — the engine leans into a high-conviction confirmation. Below `0.10` it clamps at 0.5× (half clip), above `0.30` at 1.5× (cap). The clamps protect downstream risk checks from a runaway recommendation.

`sizing-mode=NONE` (the current default) leaves the quantity untouched and is what every existing test fixture assumes.

### Why a separate `MlSignalGate` class

The gate is a pure decision function — no I/O, no Spring context — so it's straightforward to mutation-test and to spike-test in isolation. The same is true of the policy enums in `MlConfig`: `VetoMode` and `SizingMode` keep the policy explicit instead of hidden behind a chain of booleans, and changes ship as one-line YAML edits.

```java
// MlSignalGate.decide(OrderSignal, Optional<MlSignalResult>) returns MlGateDecision:
record MlGateDecision(Outcome outcome, Optional<OrderSignal> signal, double quantityScale) {
  enum Outcome { CONFIRMED, VETOED, NO_ML, LOW_CONFIDENCE, NEUTRAL_PASS, OVERRIDDEN }
}
```

---

## RFQ Pricing Engine (issues 2.4.1 + 2.4.2)

The RFQ pricing engine answers the question "what two-way price would the desk quote a client asking for *N* shares of *X*, given our current inventory, what the market is doing right now, and how much of the day's typical volume the order represents?" See [`strategies/rfq-pricing.md`](../strategies/rfq-pricing.md) for the maths in full; this section explains how the components plug into the rest of the service.

### Pricing pipeline

```
1. MarketStateCache.snapshot(symbol)           → mid (or 503 if no book yet)
2. PositionLookup.fetch(symbol)                → net inventory (or flat on timeout)
3. inventorySkew = λ · (inventory · mid) / neutralNotional       [capped]
4. adjustedMid = mid · (1 − inventorySkew)
5. VolatilityTracker.realizedVolBps(symbol)    → realized vol over the rolling window
6. volWidening = volScalar · realizedVolBps
7. advFraction = quantity / RfqSymbolReferenceData.advOf(symbol)
8. advWidening = advScalar · advFraction · 10_000
9. halfSpread = baseSpread/2 + volWidening + advWidening
10. bid = adjustedMid · (1 − halfSpread/10_000)
    ask = adjustedMid · (1 + halfSpread/10_000)
```

The full breakdown is returned in the response so the UI can show what drove the quote, and the same data lands on Prometheus labels for after-the-fact analysis.

### Components

| Component | Role |
|---|---|
| `MarketStateCache` | Per-symbol bid/ask/last/mid + bounded ring of recent mid-prices. Populated by the existing tick consumer — zero added Kafka load. |
| `VolatilityTracker` | Sample stdev of log-returns over the rolling window, expressed in bps. Returns 0 when fewer than 2 samples are available. |
| `PositionLookup` | `java.net.http.HttpClient` call to `ORDER_MANAGER_BASE_URL/api/positions/{symbol}`. 404 → flat. Timeout / 5xx → `unavailable` (engine treats as flat). 500 ms timeout by default. |
| `RfqSymbolReferenceData` | Symbol-keyed ADV / sector / beta, loaded from `strategy-engine.rfq.reference-data.*`. Mirrors the execution-engine's pattern so each service owns its own reference data (TDD §5.4). |
| `RfqPricingEngine` | The orchestrator — pure function once its dependencies are constructed. |
| `RfqQuoteStore` | In-memory `ConcurrentHashMap<UUID, RfqQuote>` with TTL eviction on read. Required so `/accept` can validate freshness. |
| `RfqController` | `POST /api/rfq/quote`, `POST /api/rfq/accept`, `GET /api/rfq/quotes/{id}`. |
| `RfqMetrics` | Counter / distribution-summary registrations for every component of the breakdown. |

### Accept path

`POST /api/rfq/accept` validates the quote id + freshness + that the supplied price matches the quoted bid (SELL) or ask (BUY) within a 0.01 tolerance. On success it constructs an `OrderSignal` with `strategyName="RFQ"`, `orderType=LIMIT`, and the quoted price, then routes it through the same `SignalPublisher` the strategy pipeline uses. Downstream the Execution Engine sees a normal limit order — the SOR, risk checks, and venue adapters are unaware that this signal originated from an RFQ. End-to-end the verified path is:

```
POST /api/rfq/accept ─► strategy.signals ─► Execution Engine
                                            ├─► risk-check chain
                                            └─► SOR ─► venue (often INTERNAL_CROSS for desk-vs-desk flow)
                                                       └─► fill ─► orders.lifecycle
                                                                    └─► Order Manager (position opens)
```

### Configuration

```yaml
strategy-engine:
  rfq:
    base-spread-bps: 4.0                    # symmetric base spread (bid/ask each get half)
    inventory-lambda: 1.0                   # 2.4.1 — skew strength
    inventory-neutral-notional: 1000000     # 2.4.1 — denominator for the skew fraction
    inventory-max-skew-bps: 30.0            # 2.4.1 — runaway-position protection
    vol-scalar: 0.50                        # 2.4.2 — multiplier on realized vol
    adv-scalar: 0.30                        # 2.4.2 — multiplier on size/ADV
    quote-validity-ms: 10000                # quote TTL
    order-manager-base-url: ${ORDER_MANAGER_BASE_URL:http://localhost:8086}
    position-lookup-timeout-ms: 500
    volatility-window-size: 30
    reference-data:
      defaults: { sector: UNKNOWN, beta: 1.0, adv: 0 }
      symbols:
        - { symbol: AAPL, sector: TECH, beta: 1.20, adv: 60000000 }
        - { symbol: MSFT, sector: TECH, beta: 0.95, adv: 25000000 }
        # ... see application.yml for the full list
```

---

## REST API

| Endpoint | Purpose |
|---|---|
| `GET /api/strategies` | List registered strategies. |
| `PUT /api/strategies/{symbol}` `{"strategyName":"VWAP"}` | Bind a strategy to a symbol. |
| `GET /api/strategies/{symbol}/active` | What's bound to this symbol right now. |
| `GET /api/strategies/{name}/parameters` | Read a strategy's parameters. |
| `PUT /api/strategies/{name}/parameters` | Update a strategy's parameters. |
| `POST /api/rfq/quote` `{"symbol","quantity"}` | Build a two-way quote. |
| `POST /api/rfq/accept` `{"quoteId","side","price"}` | Accept a previously issued quote. Returns the published `OrderSignal`. |
| `GET /api/rfq/quotes/{quoteId}` | Look up a previously issued quote (debug). |

Status codes worth knowing for `/rfq`:

| Code | Meaning |
|---|---|
| `200 OK` | Quote issued / accepted. |
| `400 Bad Request` | Missing fields, non-positive quantity, blank symbol. |
| `409 Conflict` | Accept price doesn't match the quoted bid (SELL) or ask (BUY). |
| `410 Gone` | Quote id unknown or already expired (past `quote-validity-ms`). |
| `503 Service Unavailable` | No market data for that symbol yet. |

---

## Metrics

Strategy pipeline:

| Metric | Type | Labels | Source |
|---|---|---|---|
| `mariaalpha_strategy_signals_total` | counter | `strategy`, `direction` | per signal published |
| `mariaalpha_strategy_evaluation_duration_ms` | timer | `strategy` | `onTick` + `evaluate` |
| `mariaalpha_strategy_ml_latency_ms` | timer | — | gRPC round-trip |
| `mariaalpha_strategy_ml_circuit_breaker_state` | gauge | — | 0=CLOSED, 1=HALF_OPEN, 2=OPEN |
| `mariaalpha_strategy_ml_decisions_total` | counter | `outcome`, `strategy`, `side` | gate decision per signal (2.3.2) |
| `mariaalpha_strategy_ml_quantity_scale` | distribution | `strategy` | scale factor applied on CONFIRMED (2.3.2, SCALED mode) |

RFQ pipeline:

| Metric | Type | Labels |
|---|---|---|
| `mariaalpha_rfq_quotes_total` | counter | `symbol` |
| `mariaalpha_rfq_accepts_total` | counter | `symbol`, `side` |
| `mariaalpha_rfq_inventory_skew_bps` | distribution | `symbol` |
| `mariaalpha_rfq_vol_widening_bps` | distribution | `symbol` |
| `mariaalpha_rfq_adv_widening_bps` | distribution | `symbol` |
| `mariaalpha_rfq_total_half_spread_bps` | distribution | `symbol` |

All Spring Boot Actuator + Micrometer defaults are also on: JVM, HTTP, Kafka consumer lag, system CPU/memory.

---

## Health & Readiness

| Endpoint | Checks |
|---|---|
| `GET /actuator/health/liveness` | JVM up; Spring context refreshed. |
| `GET /actuator/health/readiness` | Above + Kafka broker reachable + `mlSignal` health indicator. |
| `mlSignal` health indicator | UP when the ML circuit breaker is `CLOSED`; UNKNOWN when `HALF_OPEN`; DOWN when `OPEN`. |

The Order Manager HTTP dependency is intentionally **not** wired into readiness — the RFQ engine degrades to a flat-position quote when OM is unreachable, so an OM outage is a degradation, not a strategy-engine outage.

---

## Testing

### Unit

- `MlSignalGateTest` — 16 scenarios covering every (vetoMode × outcome × NEUTRAL × sizing) combination, including float-precision clamps.
- `MarketStateCacheTest`, `VolatilityTrackerTest` — pure-Java cache + stdev maths.
- `PositionLookupTest` — uses `HttpClient` mocks to validate 200 / 404 / 5xx / IOException fall-throughs.
- `RfqPricingEngineTest` — flat / long / short inventory, vol and ADV widening, expired-quote handling, blank-symbol rejection.
- `RfqQuoteStoreTest`, `RfqSymbolReferenceDataTest`, `RfqControllerTest` — MockMvc-driven validation of every error path.
- `StrategyEvaluationServiceTest` — Mockito-based tests for all five gate outcomes plus the sizing path.

### Integration (`-PincludeTags=integration`)

- `TickConsumerIntegrationTest` — spins a Testcontainers Kafka, wires a real strategy through Spring, and asserts that ticks on `market-data.ticks` produce signals on `strategy.signals` (six scenarios across VWAP / TWAP / IS / POV / CLOSE / Momentum).
- `RfqEndToEndIntegrationTest` — Testcontainers Kafka + MockMvc + `@MockitoSpyBean SignalPublisher`. Drives `POST /quote → POST /accept` and verifies the resulting `OrderSignal` (with `strategyName="RFQ"`) lands on the topic.

### End-to-end (`-PincludeTags=e2e`)

- `SimulatedHappyPathE2ETest.rfqQuoteReturnsTwoWayBookAndAcceptPublishesOrderSignal` — boots the full docker-compose stack, waits for the simulator to prime `MarketStateCache` for AAPL, requests a quote via the api-gateway, accepts BUY at the quoted ask, and asserts the round-trip.

---

## Deploy & operations

### Docker Compose

```yaml
strategy-engine:
  depends_on: { kafka: { condition: service_healthy }, ml-signal-service: { condition: service_healthy } }
  environment:
    KAFKA_BOOTSTRAP_SERVERS: kafka:9094
    ML_SERVICE_HOST: ml-signal-service
    ML_SERVICE_PORT: 50051
    ORDER_MANAGER_BASE_URL: http://order-manager:8086      # added for RFQ 2.4.1
    MANAGEMENT_PORT: 8083
  ports: ["8082:8082", "8083:8083"]
```

### Kubernetes (Helm)

The umbrella chart at `charts/mariaalpha/` ships the strategy-engine as a single-replica `Deployment` by default. HPA templates are present behind `autoscaling.enabled` for the cloud overlay — see TDD §10.2 for the Phase-2 sizing targets.

### Resilience

| Pattern | Where | Knobs |
|---|---|---|
| Circuit breaker | ML gRPC call | sliding window 5, 100% failure rate threshold, open 30 s, 1 permitted half-open call |
| Timeout | ML gRPC call | 500 ms per call |
| Timeout | Order Manager HTTP (RFQ inventory lookup) | 500 ms; falls back to flat position |
| Graceful degradation | Gate when ML is unavailable | Strategy signal published unchanged (outcome=NO_ML) |
| Graceful degradation | RFQ when Order Manager is unavailable | Quote is built with `inventoryNetQuantity=0` (treated as flat) |

---

## References

- **TDD:** [`technical-design-document.md`](../technical-design-document.md) — §3.2 (FR-7..12), §3.3 (FR-12 ML integration), §3.8 (FR-40 RFQ pricing), §5.2.2 (Strategy Engine), §5.3.1 (Strategy Registry), §5.4 (data model), §7 (resilience), §8.2 (metrics).
- **Sibling explainers:** [`execution-engine.md`](execution-engine.md), [`ml-signal-service.md`](ml-signal-service.md), [`order-manager.md`](order-manager.md), [`api-gateway.md`](api-gateway.md).
- **Per-strategy deep dives:** [`strategies/vwap.md`](../strategies/vwap.md), [`twap.md`](../strategies/twap.md), [`momentum.md`](../strategies/momentum.md), [`implementation-shortfall.md`](../strategies/implementation-shortfall.md), [`pov.md`](../strategies/pov.md), [`close.md`](../strategies/close.md), [`rfq-pricing.md`](../strategies/rfq-pricing.md).
- **Source root:** [strategy-engine/src/main/java/com/mariaalpha/strategyengine/](../../strategy-engine/src/main/java/com/mariaalpha/strategyengine/).
- **Configuration:** [strategy-engine/src/main/resources/application.yml](../../strategy-engine/src/main/resources/application.yml).
