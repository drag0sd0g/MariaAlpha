# Execution Engine — How It Works

## Overview

The Execution Engine is a Java 21 / Spring Boot 3 microservice that sits at the end of the MariaAlpha trading pipeline. It receives order signals from the strategy engine via Kafka, validates and risk-checks each order, routes it to an exchange adapter (simulated or Alpaca), tracks the full order lifecycle, and publishes all state transitions back to Kafka. It is the only component that interacts with external exchanges — everything upstream (market data, strategy evaluation, ML signals) feeds into it.

The service has two HTTP interfaces:

| Interface | Port | Protocol | Purpose |
|-----------|------|----------|---------|
| REST API | 8084 | HTTP/1.1 + JSON | `/api/execution/status`, `/api/execution/resume`, `/api/execution/orders/*` (manual entry + iceberg progress), `/api/routing/*` (SOR preview, venue health) |
| Actuator | 8085 | HTTP/1.1 + JSON | `/actuator/health`, `/actuator/prometheus` — ops and observability |

---

## Architecture

```
                       ┌──────────────┐
                       │    Kafka     │
                       │  (inbound)   │
                       └──────┬───────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               │
     strategy.signals   market-data.ticks     │
              │               │               │
              ▼               ▼               │
      SignalConsumer    MarketDataConsumer     │
              │               │               │
              │               ▼               │
              │       MarketStateTracker      │
              │       (bid/ask/last per       │
              │        symbol)                │
              ▼                               │
     OrderExecutionService ◄──────────────────┘
              │
     ┌────────┼─────────────────┐
     │        │                 │
     ▼        ▼                 ▼
  Handler   Risk Check     Smart Order
  Registry    Chain           Router
   (7 types)  │                 │
     │   ┌────┴────┐            │
     │   │ 5 checks│            │
     │   │ in order│            │
     │   └─────────┘            │
     │                          │
     └──────────┬───────────────┘
                │
                ▼
    ┌─────────────────────────┐
    │ ICEBERG? → IcebergCoord │   parent slicing path
    │            (slices into │
    │             LIMIT       │
    │             children)   │
    └────────┬────────────────┘
             │ (all other types fall through)
             ▼
        ExchangeAdapter
         (submit order)
                │
                ▼
     ┌──────────────────┐
     │  SimulatedAdapter │  (default, @Profile("simulated"))
     │  AlpacaAdapter    │  (@Profile("alpaca"))
     └────────┬─────────┘
              │  fills arrive via callback
              ▼
     OrderLifecycleManager
      (state machine + Kafka publish)
              │
              ▼
     DailyLossMonitor
      (track P&L, halt on breach)
              │
              ▼
        ┌─────────────┐
        │    Kafka     │
        │  (outbound)  │
        └─────────────┘
          orders.lifecycle
          routing.decisions
          analytics.risk-alerts
```

---

## Signal Processing Pipeline

When an `OrderSignal` arrives on the `strategy.signals` Kafka topic, it passes through a six-stage pipeline. Each stage can reject the signal, short-circuiting the rest. This section walks through each stage in detail.

### Stage 0: Trading Halt Check

Before any processing, the service checks whether the `DailyLossMonitor` has halted trading. If cumulative realised P&L for the day has breached the configured loss limit, all incoming signals are immediately rejected. This is the cheapest possible check — a single `AtomicBoolean.get()` — and it sits at the top to avoid wasting work on orders that will never execute.

```java
if (dailyLossMonitor.isTradingHalted()) {
    metrics.recordRejection("TradingHalted");
    return;
}
```

### Stage 1: Order Creation

The signal is wrapped in a mutable `Order` object. The order gets a random UUID as its `orderId`, copies all fields from the signal (symbol, side, quantity, order type, limit/stop prices, strategy name), and starts in `OrderStatus.NEW`. This order is immediately registered with the `OrderLifecycleManager`, which publishes a `NEW` event to Kafka.

The `Order` class is intentionally mutable — it accumulates fills, tracks filled quantity, computes a running weighted-average fill price, and its status changes over time. Thread safety is achieved via `AtomicReference<OrderStatus>` for status transitions and `synchronized` for the `addFill()` method.

### Stage 2: Order Type Validation

The `OrderTypeHandlerRegistry` looks up the handler for the order's type. As of issue 2.1.4 seven order types are supported: **MARKET, LIMIT, STOP, IOC, FOK, GTC, ICEBERG**. If no handler is registered (e.g., a Phase-3 type like PEGGED arrives from a future strategy version), the order is rejected with `"Unsupported order type"`.

Each handler performs type-specific validation:

**MarketOrderHandler:**
- Quantity must be positive
- Market data must be available for the symbol
- Emits `TimeInForce.DAY`

**LimitOrderHandler:**
- All of the above, plus:
- Limit price must be present and positive
- Emits `TimeInForce.DAY`

**StopOrderHandler:**
- All of the above, plus:
- Stop price must be present and positive
- BUY STOP price must be above the current ask (momentum entry — you only want to buy if the price breaks through a resistance level)
- SELL STOP price must be below the current bid (stop-loss — you only want to sell if the price drops below a support level)
- Emits `TimeInForce.DAY`

**IocOrderHandler** (Immediate-Or-Cancel):
- Quantity > 0, positive limit price, market state present
- Pre-flight marketability check: rejects with a clear validation error if a BUY limit is below the ask or a SELL limit is above the bid — an IOC submitted in that state would cancel for zero fills, so we reject up-front rather than emit an opaque cancel event
- Emits `TimeInForce.IOC`

**FokOrderHandler** (Fill-Or-Kill):
- Same as IOC validation; FOK is all-or-nothing immediate. The MVP simulator uses a top-of-book approximation; depth-aware partials are deferred to issue 2.1.10
- Emits `TimeInForce.FOK`

**GtcOrderHandler** (Good-Till-Cancel):
- Identical validation to LIMIT; GTC is a TIF overlay that changes when the order survives on the book (across days for Alpaca), not how it is matched
- Emits `TimeInForce.GTC`

**IcebergOrderHandler**:
- Quantity > 0, positive limit price, **`displayQuantity` > 0 and strictly less than `quantity`**, market state present
- The handler's `toExecutionInstruction` populates `ExecutionInstruction.displayQuantity` so the value flows downstream, but the parent order never reaches a venue adapter directly — see the iceberg dispatch below
- Emits `TimeInForce.DAY` (TIF applies to each child slice individually)

Wire-format translation lives in `AlpacaOrderTypeMapper`, which collapses IOC/FOK/GTC into Alpaca's `type=limit` with the appropriate `time_in_force` and explicitly rejects ICEBERG (the slicer is responsible for issuing the child LIMITs).

The handler pattern remains extensible: adding a new order type (e.g. PEGGED — Phase 3) only requires implementing the `OrderTypeHandler` interface and annotating with `@Component`. Spring auto-discovers it and the registry picks it up. No existing code changes.

#### ICEBERG dispatch (special case)

Immediately after validation + risk checks, `OrderExecutionService.processOrder` branches on `OrderType.ICEBERG` and hands the parent off to `IcebergCoordinator.onParentSubmit` *instead of* the normal venue-adapter call. The coordinator:

1. Registers the parent in the in-memory `ParentChildOrderRegistry` along with its target `displayQuantity`
2. Builds a child LIMIT `Order` (via `IcebergSliceFactory`) sized to `min(remaining, displayQuantity)`, with `parentOrderId` linking it back to the parent and `strategyName="ICEBERG-CHILD"`
3. Routes the child through the SOR, registers it with the lifecycle manager, and submits it to the venue adapter
4. On the *first* child ack the parent transitions NEW → SUBMITTED

Subsequent slices are issued from `OrderExecutionService.onExecutionReport` via the `icebergCoordinator.onChildFillIfApplicable(order, report)` hook (called once `dailyLossMonitor.onFill` has run). The coordinator transitions the parent into PARTIALLY_FILLED on the first child completion and into FILLED when cumulative child fills meet the parent quantity (with reason `iceberg-complete`).

A parent cancel (via `ManualOrderService.cancel`) cascades through `IcebergCoordinator.onParentCancelRequested`, which cancels the currently-active child at its venue and transitions the parent to CANCELLED with reason `iceberg-parent-cancelled`. Already-filled children are immutable; not-yet-submitted slices are simply never issued.

### Stage 3: Risk Check Chain

The order passes through a composable chain of five risk checks, executed in order. The chain **short-circuits** on the first failure — if check 2 fails, checks 3-5 are never evaluated. This is a deliberate design choice: risk checks can involve lookups against position state and market data, so skipping unnecessary checks reduces latency.

Each check returns a `RiskCheckResult` with a boolean, the check name, and a human-readable reason. The reason is included in the Kafka rejection event, making it easy to diagnose why an order was blocked.

#### Check 1: MaxOrderNotional (`@Order(1)`)

Computes `lastTradePrice × quantity` and rejects if it exceeds the configured limit (default: $100,000). This prevents a single order from taking an outsized position. It uses the last trade price rather than bid/ask because it represents the most recent actual transaction price, which is the most realistic estimate of what the order will fill at.

#### Check 2: MaxPositionPerSymbol (`@Order(2)`)

Looks up the current position notional for the symbol (tracked by `PositionTracker`) and adds the order's notional. If the projected total exceeds the per-symbol limit (default: $500,000), the order is rejected. This prevents concentration risk — even if each individual order is small, accumulated exposure to a single symbol can be dangerous.

#### Check 3: MaxPortfolioExposure (`@Order(3)`)

Computes the total gross exposure across all symbols (sum of absolute position notionals) and adds the order's notional. If the projected total exceeds the portfolio limit (default: $2,000,000), the order is rejected. This is a portfolio-level guard that limits total market exposure regardless of how well diversified the positions are.

#### Check 4: MaxOpenOrders (`@Order(4)`)

Counts the number of orders currently in SUBMITTED or PARTIALLY_FILLED status. If the count is at or above the limit (default: 50), the order is rejected. This prevents the system from accumulating too many live orders, which can cause issues with exchange rate limits and makes risk management harder.

#### Check 5: DailyLossLimit (`@Order(5)`)

Checks the `DailyLossMonitor.isTradingHalted()` flag. This is technically redundant with Stage 0, but it's included in the chain for completeness — if a loss breach occurs *between* Stage 0 and Stage 3 (due to concurrent fill processing), this check catches it. Since it's the cheapest check (a single boolean read), placing it last doesn't add meaningful latency.

### Stage 4: Routing

The `SmartOrderRouter` interface allows for venue selection logic — choosing between multiple exchanges or dark pools based on order characteristics, market conditions, or historical fill quality. Issue 2.1.1 introduced `ScoredSmartOrderRouter`, which weighs each configured venue across four scorers (latency, fees, information-leakage, liquidity / price-improvement) and writes the full breakdown to the `routing.decisions` Kafka topic. The legacy `DirectRouter` remains as a pass-through fallback. See `docs/smart-order-router-explainer.md` for the scoring model.

Iceberg child slices flow through the same router — each child is routed independently, so a multi-venue split across an ICEBERG parent is possible.

### Stage 5: Submission

The handler builds an `ExecutionInstruction` from the order (a typed `TimeInForce`, an adjusted limit price, and an optional `displayQuantity` for ICEBERG children), and the instruction is submitted to the exchange adapter. The adapter returns an `OrderAck`:

- **Accepted**: The order transitions to `SUBMITTED` and the exchange order ID is stored on the order for fill correlation.
- **Rejected**: The order transitions to `REJECTED` with the adapter's rejection reason.

For ICEBERG parents this stage is bypassed (see "ICEBERG dispatch" above) — the coordinator owns submission and lifecycle for the parent.

---

## Exchange Adapters

The `ExchangeAdapter` interface defines five methods:

```java
OrderAck submitOrder(ExecutionInstruction instruction);
OrderAck cancelOrder(String exchangeOrderId);
void onExecutionReport(Consumer<ExecutionReport> callback);
void start();
void shutdown();
boolean isHealthy();
```

The callback-based `onExecutionReport()` pattern (rather than a reactive `Flux<ExecutionReport>`) was chosen because the execution engine is servlet-based (Spring MVC, not WebFlux). The callback achieves the same push-based delivery of fills without pulling in the reactive stack.

### SimulatedExchangeAdapter

Active when `spring.profiles.active=simulated` (the default). This adapter fills orders locally against the current market data snapshot, simulating realistic exchange behaviour:

**MARKET orders** are scheduled for fill after a configurable latency (default: 50ms). The fill price is the current ask (for BUY) or bid (for SELL) plus a slippage factor (default: 2 basis points). Slippage is modelled as a percentage of the base price — for a $150 stock with 2bps slippage, a BUY fills at approximately $150.03.

**LIMIT orders** check whether the limit price is immediately fillable (BUY limit >= ask, or SELL limit <= bid). If yes, they schedule a fill. If not, they go into a pending map. When new market data arrives (via `onMarketUpdate()`), the adapter re-evaluates all pending LIMIT orders and fills those that are now executable.

**STOP orders** are stored as pending. When market data arrives, the adapter checks if the market price has crossed the stop trigger:
- BUY STOP: triggers when `lastTradePrice >= stopPrice` (breakout entry)
- SELL STOP: triggers when `lastTradePrice <= stopPrice` (stop-loss)

Once triggered, the STOP order converts to a MARKET order and schedules a fill.

**GTC orders** are handled identically to LIMIT in-process (the simulator has no concept of overnight sessions; GTC's "survives across days" semantic only matters to Alpaca).

**IOC orders** compute marketability against the current bid/ask snapshot:
- Marketable (BUY limit ≥ ask, or SELL limit ≤ bid): schedules a normal fill against the top-of-book approximation
- Non-marketable: emits an `ExecutionReport(fillQuantity=0, remainingQuantity=0, reason="ioc-residual-cancel")` after `fillLatencyMs`, which `OrderExecutionService.onExecutionReport` translates into a CANCELLED transition. The `mariaalpha.execution.ioc.residual.cancelled.total` counter is incremented for that branch.

**FOK orders** apply the same marketability check; non-marketable orders emit `reason="fok-killed"` (recorded via `mariaalpha.execution.fok.killed.total`). FOK is all-or-nothing — there is no partial-fill path.

**ICEBERG orders** never reach `SimulatedExchangeAdapter.submitOrder` directly. The adapter's switch fails closed for `ICEBERG` with `accepted=false, reason="ICEBERG orders must be sliced by IcebergCoordinator, not submitted directly"` as a defence-in-depth check; the real path goes parent → IcebergCoordinator → child LIMITs through the regular pipeline. Each child fills like any other LIMIT.

The fill quantity is controlled by `partialFillRatio` (default: 1.0 = always full fill). Setting this to 0.5 would simulate partial fills, where each fill covers 50% of the remaining quantity.

### AlpacaExchangeAdapter

Active when `spring.profiles.active=alpaca`. This adapter connects to Alpaca Markets for live or paper trading:

**Order submission** uses Java's built-in `HttpClient` to POST to `/v2/orders` with API key authentication. The request body maps the order fields to Alpaca's API format via `AlpacaOrderTypeMapper`:

| Internal `OrderType` | Alpaca `type` | Alpaca `time_in_force` |
|----------------------|---------------|------------------------|
| MARKET               | `market`      | `day`                  |
| LIMIT                | `limit`       | `day`                  |
| STOP                 | `stop`        | `day`                  |
| IOC                  | `limit`       | `ioc`                  |
| FOK                  | `limit`       | `fok`                  |
| GTC                  | `limit`       | `gtc`                  |
| ICEBERG              | (mapper throws — children go via LIMIT) | n/a       |

**Fill reception** uses an OkHttp WebSocket connection to Alpaca's `trade_updates` stream. The `AlpacaWebSocketListener`:
1. Authenticates on connection with the API key/secret
2. Subscribes to the `trade_updates` stream
3. Parses `fill` and `partial_fill` events into `ExecutionReport` objects
4. Invokes the registered callback to push fills into the lifecycle manager

**Health** is determined by the WebSocket connection state — `isHealthy()` returns `true` only when the WebSocket is connected.

---

## Order Lifecycle State Machine

Every order passes through a well-defined state machine. The states and transitions are encoded directly in the `OrderStatus` enum:

```
                    ┌─────────┐
                    │   NEW   │
                    └────┬────┘
                   ┌─────┼─────┐
                   │           │
                   ▼           ▼
             ┌──────────┐ ┌──────────┐
             │SUBMITTED │ │ REJECTED │ (terminal)
             └────┬─────┘ └──────────┘
            ┌─────┼──────────────┐
            │     │              │
            ▼     ▼              ▼
    ┌───────────┐ ┌──────┐ ┌──────────┐
    │PARTIALLY  │ │FILLED│ │CANCELLED │
    │  FILLED   │ │      │ │          │
    └─────┬─────┘ └──────┘ └──────────┘
          │       (terminal)  (terminal)
     ┌────┼────┐
     │         │
     ▼         ▼
  ┌──────┐ ┌──────────┐
  │FILLED│ │CANCELLED │
  └──────┘ └──────────┘
```

**State transition enforcement** uses `AtomicReference.compareAndSet()`. Each `OrderStatus` defines its `allowedTransitions()` set. Before transitioning, the manager checks `canTransitionTo()` and then performs an atomic CAS. If the CAS fails (because another thread already changed the status), an `IllegalStateTransitionException` is thrown.

This design guarantees:
- **No invalid transitions** — you can't go from FILLED back to SUBMITTED
- **At-most-once semantics** — concurrent fills on the same order don't corrupt state
- **Full auditability** — every transition is published to the `orders.lifecycle` Kafka topic as an `OrderEvent`

### Fill Processing

When an `ExecutionReport` arrives from the exchange adapter (via the registered callback):

1. The `OrderLifecycleManager` looks up the order by exchange order ID
2. **Zero-quantity terminal event branch**: if both `fillQuantity == 0` and `remainingQuantity == 0`, the report is treated as a cancellation. The order is transitioned to CANCELLED with the report's `reason` (or `"exchange-cancel"` if absent), and the IOC-residual-cancel / FOK-killed counters are incremented for those specific reasons. If the order is already terminal (e.g. a prior PARTIAL → FILLED on IOC), the `IllegalStateTransitionException` is swallowed and the event is dropped.
3. For positive-quantity fills, a `Fill` record is created with the fill price, quantity, venue, and timestamp
4. The order transitions to `PARTIALLY_FILLED` (if remaining quantity > 0) or `FILLED` (if remaining = 0)
5. The fill is added to the order, which recomputes the weighted-average fill price:

```
avgFillPrice = (prevAvg × prevFilledQty + fillPrice × fillQty) / newFilledQty
```

6. The `DailyLossMonitor` is notified of the fill to update the daily P&L
7. **Iceberg hook**: `icebergCoordinator.onChildFillIfApplicable(order, report)` runs last. It no-ops unless the order has a `parentOrderId` known to the registry, in which case it updates the parent's progress and submits the next slice (or transitions the parent to FILLED).

---

## Daily Loss Limit

The `DailyLossMonitor` tracks cumulative realised P&L throughout the trading day and halts all trading if losses exceed the configured threshold (default: $25,000).

### How P&L is Calculated

On every fill, the monitor computes:

```
pnl = (fillPrice - entryPrice) × fillQuantity
```

Where `entryPrice` is the order's current weighted-average fill price at the time of the fill. This is a simplified P&L model — it tracks the difference between exit and entry prices rather than full mark-to-market. The P&L is accumulated atomically using `AtomicReference.accumulateAndGet()` for lock-free thread safety.

### Halt Trigger

After each P&L update, the monitor checks if the negated daily P&L exceeds the loss limit. If it does:

1. An `AtomicBoolean` `tradingHalted` flag is set to `true`
2. A `CRITICAL` risk alert is published to the `analytics.risk-alerts` Kafka topic
3. All subsequent order signals are rejected at Stage 0 of the pipeline

### Recovery

There are two ways to resume trading after a halt:

**Manual resume** — A human operator sends `POST /api/execution/resume`. This clears the `tradingHalted` flag but does not reset the daily P&L. The intention is that a risk officer reviews the situation and makes a conscious decision to continue.

**Automatic reset** — At 09:30 ET every weekday (market open), a `@Scheduled` cron task resets both the daily P&L to zero and the halt flag to false. This ensures the system starts each trading day fresh.

---

## Resilience Patterns

When running with the `alpaca` profile, the adapter is protected by five Resilience4j patterns configured in `ResilienceConfig`. These follow the decoration order specified in the TDD: Bulkhead, Rate Limiter, Retry, Circuit Breaker, Timeout.

### Thread Pool Bulkheads

Three separate thread pools isolate different workloads:

| Bulkhead | Core / Max Threads | Queue | Purpose |
|----------|-------------------|-------|---------|
| `orderSubmission` | 5 / 10 | 50 | Alpaca REST API calls for order submission |
| `fillProcessing` | 3 / 5 | 100 | Processing execution reports from WebSocket |
| `riskCheck` | 3 / 5 | 50 | Risk check chain evaluation |

This isolation ensures that a slow Alpaca API doesn't starve fill processing or risk checks.

### Rate Limiter

Limited to 200 requests per minute, matching Alpaca's documented rate limit. Requests that exceed the limit are queued for up to 10 seconds before being rejected.

### Retry

Failed API calls are retried up to 3 times with exponential backoff starting at 500ms. This handles transient network errors and brief Alpaca outages.

### Circuit Breaker

Configured with a sliding window of 5 calls and a 60% failure threshold (3 out of 5). When the circuit opens, it stays open for 60 seconds, then transitions to half-open (allowing 1 probe call). This prevents the system from hammering a failing Alpaca API.

### Timeout

Each Alpaca REST API call has a 5-second timeout. On timeout, the call is treated as a failure (feeding into the retry and circuit breaker counts).

---

## Threading Model

| Thread Pool | Runs | Notes |
|-------------|------|-------|
| Tomcat thread pool | REST endpoints (`/api/execution/*`) | Standard Spring MVC |
| Spring Kafka consumer | `SignalConsumer` — polls `strategy.signals` | Single-threaded per partition |
| Spring Kafka consumer | `MarketDataConsumer` — polls `market-data.ticks` | Separate consumer group |
| `ScheduledExecutorService` | `SimulatedExchangeAdapter` — delayed fills | Single thread for simulated mode |
| OkHttp WebSocket | `AlpacaWebSocketListener` — trade updates | Single thread for Alpaca mode |
| Resilience4j bulkheads | Order submission, fill processing, risk checks | Alpaca profile only |

**Thread safety design:**
- `Order.status` uses `AtomicReference<OrderStatus>` with CAS for lock-free state transitions
- `Order.addFill()` is `synchronized` because it updates multiple correlated fields (fills list, filledQuantity, avgFillPrice)
- `OrderLifecycleManager.orders` is a `ConcurrentHashMap`
- `DailyLossMonitor.dailyPnl` uses `AtomicReference<BigDecimal>` with `accumulateAndGet()`
- `DailyLossMonitor.tradingHalted` is an `AtomicBoolean`
- `MarketStateTracker` and `PositionTracker` use `ConcurrentHashMap`
- `SimulatedExchangeAdapter.pendingOrders` is a `ConcurrentHashMap`

---

## Kafka Integration

### Inbound Topics

**`strategy.signals`** — consumed by `SignalConsumer`. Each message is a JSON-serialised `OrderSignal` with fields: `symbol`, `side`, `quantity`, `orderType`, `limitPrice` (nullable), `stopPrice` (nullable), `strategyName`, `timestamp`, plus the Phase 2 additions `displayQuantity` (nullable Integer; ICEBERG parents only), `tif` (nullable `TimeInForce`; populated for manual IOC/FOK/GTC submissions), and `parentOrderId` (nullable String; set on iceberg children). The consumer deserialises using Jackson and delegates to `OrderExecutionService.executeSignal()`. Strategies that emit only the 8 MVP fields still deserialise correctly — the three new fields are optional.

**`market-data.ticks`** — consumed by `MarketDataConsumer` in a separate consumer group (`execution-engine-market-data`). Each tick is parsed into a `MarketState` (symbol, bidPrice, askPrice, lastTradePrice, timestamp) and stored in the `MarketStateTracker`. This provides the risk checks and order type handlers with current market prices.

### Outbound Topics

**`orders.lifecycle`** — published by `OrderEventPublisher` on every state transition. Each message is an `OrderEvent` containing the orderId (used as the Kafka key for ordering), the new status, a full `OrderSnapshot` (now including `displayQuantity`, `tif`, and `parentOrderId`), the fill (if this transition was caused by a fill), the rejection reason (if rejected), and a timestamp. Downstream consumers (Order Manager, Analytics, Reconciliation) use this topic to maintain their own views of order state.

**`routing.decisions`** — published by `RoutingDecisionPublisher` whenever an order is routed. Contains the orderId, selected venue, the full per-venue score breakdown, and the routing reason. Since issue 2.1.1 this shows the venue chosen by `ScoredSmartOrderRouter` (typically one of `SIMULATED`, `DARK_POOL_A`, `INTERNAL_CROSS` — whichever wins on the composite latency / fees / information-leakage / liquidity score). The legacy `DirectRouter` remains as a one-venue fallback when explicitly selected; that mode emits venue="PRIMARY".

**`analytics.risk-alerts`** — published by `RiskAlertPublisher` when the daily loss limit is breached. Contains the symbol, alert type (`DAILY_LOSS_LIMIT_BREACH`), severity (`CRITICAL`), a human-readable message, and a timestamp.

---

## Observability

### Health Indicators

Two custom health indicators are registered with Spring Boot Actuator:

**ExchangeAdapterHealthIndicator** — reports UP if `adapter.isHealthy()` returns true. For the simulated adapter, this is always UP. For Alpaca, it reflects the WebSocket connection state.

**TradingHaltHealthIndicator** — reports WARN (not DOWN) if trading is halted due to a daily loss breach. The WARN status avoids triggering Kubernetes readiness probe failures — the service is technically operational, just not accepting new orders. The indicator includes the current daily P&L in its details.

### Prometheus Metrics

The following metrics are exported at `/actuator/prometheus`:

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `mariaalpha.execution.order.latency.ms` | Timer | — | End-to-end latency from signal arrival to order submission, with p50/p95/p99 percentiles |
| `mariaalpha.execution.risk.check.duration.ms` | Timer | — | Risk check chain evaluation duration |
| `mariaalpha.execution.sor.scoring.duration.ms` | Timer | — | Smart Order Router scoring loop duration (Phase 2.1.1) |
| `mariaalpha.execution.risk.rejections.total` | Counter | `reason` | Count of rejected orders by rejection reason (TradingHalted, ValidationFailed, MaxOrderNotional, IcebergFirstSliceRejected, etc.) |
| `mariaalpha.execution.orders.submitted.total` | Counter | `side` (also `type` via overload) | Count of successfully submitted orders |
| `mariaalpha.execution.fills.total` | Counter | `symbol` | Count of fills received by symbol |
| `mariaalpha.execution.venue.submit.total` | Counter | `venue`, `venue_type` | Per-venue submission count (Phase 2.1.1 SOR) |
| `mariaalpha.execution.venue.fills.total` | Counter | `venue`, `venue_type` | Per-venue fill count |
| `mariaalpha.execution.sor.routing.total` | Counter | `venue`, `venue_type` | Per-venue routing-decision count |
| `mariaalpha.execution.sor.candidate.score` | DistributionSummary | `venue`, `venue_type` | Histogram of per-venue scores observed during routing |
| `mariaalpha.execution.ioc.residual.cancelled.total` | Counter | `symbol`, `side` | Count of IOC orders that cancelled with zero fills (Phase 2.1.3) |
| `mariaalpha.execution.fok.killed.total` | Counter | `symbol`, `side` | Count of FOK orders that killed for insufficient liquidity (Phase 2.1.3) |
| `mariaalpha.execution.iceberg.slices.total` | Counter | `parent_symbol`, `side` | Count of iceberg child slices submitted (Phase 2.1.4) |
| `mariaalpha.execution.iceberg.parent.duration.ms` | Timer | `parent_symbol` | Wall-clock duration from parent SUBMITTED to FILLED (Phase 2.1.4) |

---

## Configuration Reference

All settings live under the `execution-engine.*` namespace and are overridable via environment variables (Spring relaxed binding).

### Risk Limits (`execution-engine.risk.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `max-order-notional` | `100000` | Maximum notional value of a single order ($) |
| `max-position-per-symbol` | `500000` | Maximum accumulated position notional per symbol ($) |
| `max-portfolio-exposure` | `2000000` | Maximum total gross portfolio exposure ($) |
| `max-open-orders` | `50` | Maximum number of concurrent open orders |
| `max-daily-loss` | `25000` | Daily loss threshold that triggers trading halt ($) |

### Kafka Topics (`execution-engine.kafka.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `signals-topic` | `strategy.signals` | Topic for incoming order signals |
| `market-data-topic` | `market-data.ticks` | Topic for market data ticks |
| `orders-lifecycle-topic` | `orders.lifecycle` | Topic for order lifecycle events |
| `routing-decisions-topic` | `routing.decisions` | Topic for routing decisions |
| `risk-alerts-topic` | `analytics.risk-alerts` | Topic for risk alerts |

### Simulated Adapter (`execution-engine.simulated.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `fill-latency-ms` | `50` | Simulated fill delay in milliseconds |
| `slippage-bps` | `2` | Price slippage in basis points |
| `partial-fill-ratio` | `1.0` | Fraction of remaining quantity to fill (1.0 = always full) |
| `venue` | `SIMULATED` | Venue name reported in fills |

### Alpaca Adapter (`execution-engine.alpaca.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `api-key` | (required) | Alpaca API key ID |
| `api-secret` | (required) | Alpaca API secret key |
| `base-url` | `https://paper-api.alpaca.markets` | Alpaca REST API base URL |
| `data-url` | `https://data.alpaca.markets` | Alpaca Data API base URL |
| `websocket-url` | `wss://paper-api.alpaca.markets/stream` | Alpaca WebSocket URL |
| `venue` | `ALPACA` | Venue name reported in fills |

---

## Design Decisions and Trade-offs

### Model Replication vs Shared Module

The execution engine needs `OrderSignal`, `Side`, and `OrderType` — models that also exist in the strategy engine. Rather than creating a shared-models library, these are replicated locally under `com.mariaalpha.executionengine.model`. This avoids coupling two independently deployable services. The field names match exactly, so Kafka JSON deserialisation works without mapping. A shared-models module will be introduced when a third consumer needs the same types.

The execution engine's `OrderType` enum is the richest copy in the system: it carries the four Phase-2 additions (IOC, FOK, GTC, ICEBERG) on top of the MVP MARKET/LIMIT/STOP. The order-manager `OrderType` mirrors it. The strategy-engine `OrderType` deliberately stays at MARKET/LIMIT — strategies only emit those two; richer types arrive through the manual REST API. Post-trade goes one further and also enumerates `STOP_LIMIT` and `PEGGED` so its TCA pipeline doesn't need a schema bump for Phase 3.

The execution engine's `OrderSignal` adds `stopPrice` (not present in the strategy engine's copy), plus the Phase-2 fields `displayQuantity`, `tif`, and `parentOrderId`. `OrderSignal` retains a backwards-compatible 8-arg constructor so existing strategy-engine + test call sites compile unchanged. Jackson ignores unknown properties by default, so strategy engine signals deserialise correctly — the missing fields are simply `null`.

### Callback-Based Fill Delivery

The TDD specifies `Flux<ExecutionReport> streamExecutionReports()` on the exchange adapter interface. The implementation simplifies this to a `Consumer<ExecutionReport>` callback. This was chosen because the execution engine is servlet-based (Spring MVC) rather than reactive (WebFlux). The callback achieves the same push-based delivery without pulling in the reactive stack.

### AtomicReference CAS vs Locks

Order status transitions use `AtomicReference.compareAndSet()` rather than `synchronized` blocks or `ReentrantLock`. CAS is lock-free and non-blocking, which avoids deadlock risks and performs well under contention. The trade-off is that CAS can fail spuriously — but in this case, that's the desired behaviour: if two threads race to transition the same order, exactly one succeeds and the other gets an exception, which is correct.

### Direct Router as SOR Stub

The `DirectRouter` is a legacy pass-through that routes every order to the single configured exchange adapter. Issue 2.1.1 introduced the production router, `ScoredSmartOrderRouter`, behind the same `SmartOrderRouter` interface. The pre-existing scaffolding meant upgrading to multi-venue routing required no changes to the orchestration service.

### Iceberg Subsystem (issue 2.1.4)

The iceberg flow is deliberately *not* implemented as another `OrderTypeHandler`. The handler interface returns a single `ExecutionInstruction` per call; iceberg needs to issue many child orders over time, react to fills, and own its own state. That's a coordinator-shaped responsibility, not a stateless validator, so it lives in a sibling `iceberg/` package:

- **`IcebergCoordinator`** — orchestrates parent → child slicing, hooks into `OrderExecutionService.processOrder` (replacing the venue-adapter call for ICEBERG parents) and `onExecutionReport` (handling child completions). Owns the parent state-machine transitions.
- **`ParentChildOrderRegistry`** — in-memory `ConcurrentHashMap`-backed maps of child→parent and per-parent `IcebergProgress`. Per-parent mutations use `ConcurrentHashMap.compute` to stay race-free under concurrent fills on sibling children.
- **`IcebergSliceFactory`** — builds child LIMIT `Order` instances. Children inherit symbol/side/limit-price, get `strategyName="ICEBERG-CHILD"`, and carry the parent's UUID in `parentOrderId`.
- **`IcebergProgress`** — immutable record holding total / display / submitted / filled quantities, slice count, and the currently-active child id.
- **`IcebergProgressView`** — REST DTO exposed via `GET /api/execution/orders/{parentId}/iceberg-progress`. Returns 404 once the parent reaches a terminal state (the registry cleans up on FILLED/CANCELLED).
- **`IcebergMetrics`** — facade for the two iceberg-specific meters.

The registry is in-memory only — there's no Postgres backing for the live parent→child relationship. Child rows in the order-manager DB carry `parent_order_id` (migration 006) so the relationship is rebuildable from durable state if the execution-engine restarts, but the *coordinator* state (current slice, submitted-but-not-acked, etc.) is lost on restart. Issue 3.4.1 (program trading) is the right ticket to add a recovery path if program-trading workloads need it.
