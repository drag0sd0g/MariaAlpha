# Order Manager — How It Works

## Overview

The Order Manager is a Java 21 / Spring Boot 3 microservice that sits **downstream** of the Execution Engine in the MariaAlpha trading pipeline. It is the system of record for orders, fills, and positions: everything the Execution Engine emits to Kafka is durably persisted here, and positions are continuously re-valued against live market data. It exposes a read-only REST API so dashboards, analytics jobs, and operators can ask "what do we own, what did we pay, and how are we doing?" without touching the execution path.

The Order Manager is deliberately **passive**. It never creates, amends, or cancels orders — those actions are owned by the Execution Engine. The Order Manager only observes the Kafka event stream and maintains a materialised view: relational tables backed by PostgreSQL, live P&L gauges backed by a scheduled mark-to-market job, and an outbound `positions.updates` Kafka topic for anything downstream that wants a push model.

The service has two HTTP interfaces:

| Interface | Port | Protocol | Purpose |
|-----------|------|----------|---------|
| REST API | 8086 | HTTP/1.1 + JSON | `/api/orders`, `/api/positions`, `/api/portfolio/summary` |
| Actuator | 8087 | HTTP/1.1 + JSON | `/actuator/health`, `/actuator/prometheus` — ops and observability |

---

## Architecture

```
                       ┌──────────────┐
                       │    Kafka     │
                       │  (inbound)   │
                       └──────┬───────┘
                              │
              ┌───────────────┴───────────────┐
              │                               │
              ▼                               ▼
     orders.lifecycle                 market-data.ticks
              │                               │
              ▼                               ▼
  OrderLifecycleConsumer              MarketDataConsumer
              │                               │
              │                               ├──► mark-price cache
              │                               │    (ConcurrentHashMap)
              ▼                               │
  OrderPersistenceService                     │
   ├── upsertOrder()  (idempotent)            │
   └── persistFillIfAbsent()  (dedup)         │
              │                               │
              ▼                               │
         PositionService ◄────────────────────┘
          ├── applyFill()     (realized P&L, weighted avg)
          └── markToMarket()  (unrealized P&L, @Scheduled 1s)
              │
     ┌────────┴────────┐
     │                 │
     ▼                 ▼
  PostgreSQL    PositionUpdatePublisher
  (orders,             │
   fills,              ▼
   positions)   ┌──────────────┐
                │    Kafka      │
                │  (outbound)   │
                └──────────────┘
                  positions.updates
```

At a glance:

- **Two inbound streams** — lifecycle events (orders + fills) and raw market ticks.
- **Three services** — persistence (JPA + dedup), position (P&L math + mark cache), portfolio (aggregations).
- **One outbound stream** — `positions.updates`, fired on every persisted fill.
- **One REST API** — five endpoints, all read-only.
- **One database** — four tables, all governed by Liquibase changesets in `src/main/resources/db/changelog/`.

---

## Data Model

Four PostgreSQL tables, all created by Liquibase changesets `001`–`004`:

### `orders` — one row per order (not per fill)

```sql
order_id             UUID PRIMARY KEY              -- internal UUID
client_order_id      VARCHAR(64) UNIQUE NOT NULL   -- idempotency key
symbol               VARCHAR(16) NOT NULL
side                 VARCHAR(4)  NOT NULL          -- 'BUY' | 'SELL'
order_type           VARCHAR(16) NOT NULL          -- 'MARKET' | 'LIMIT' | 'STOP'
quantity             DECIMAL(18,8) NOT NULL
limit_price          DECIMAL(18,8)                 -- nullable for MARKET
stop_price           DECIMAL(18,8)                 -- nullable for non-STOP
status               VARCHAR(16) NOT NULL          -- state machine
strategy             VARCHAR(32)                   -- 'VWAP', 'MOMENTUM', ...
filled_quantity      DECIMAL(18,8) NOT NULL DEFAULT 0
avg_fill_price       DECIMAL(18,8)
exchange_order_id    VARCHAR(64)                   -- broker reference
venue                VARCHAR(32)
display_quantity     DECIMAL(18,8)                 -- iceberg support
arrival_mid_price    DECIMAL(18,8)                 -- for slippage analysis
created_at           TIMESTAMPTZ NOT NULL
updated_at           TIMESTAMPTZ NOT NULL
```

Indexes: `symbol`, `status`, `created_at`, `exchange_order_id`.

### `fills` — one row per child fill

```sql
fill_id              UUID PRIMARY KEY
order_id             UUID NOT NULL REFERENCES orders(order_id)
symbol               VARCHAR(16) NOT NULL
side                 VARCHAR(4)  NOT NULL
fill_price           DECIMAL(18,8) NOT NULL
fill_quantity        DECIMAL(18,8) NOT NULL
commission           DECIMAL(18,8) NOT NULL DEFAULT 0
venue                VARCHAR(32)
exchange_fill_id     VARCHAR(64)                   -- dedup key
filled_at            TIMESTAMPTZ NOT NULL
```

Indexes: `order_id`, `symbol`, `filled_at`.

### `positions` — one row per symbol (including flat positions)

```sql
symbol               VARCHAR(16) PRIMARY KEY
net_quantity         DECIMAL(18,8) NOT NULL DEFAULT 0   -- positive=long, negative=short
avg_entry_price      DECIMAL(18,8) NOT NULL DEFAULT 0   -- weighted avg
realized_pnl         DECIMAL(18,8) NOT NULL DEFAULT 0
unrealized_pnl       DECIMAL(18,8) NOT NULL DEFAULT 0
last_mark_price      DECIMAL(18,8)
sector               VARCHAR(32)                         -- phase 2+
beta                 DECIMAL(8,4)                        -- phase 2+
updated_at           TIMESTAMPTZ NOT NULL
```

### `portfolio_snapshots` — reserved for phase 2 (scheduled snapshot writer)

```sql
snapshot_id          UUID PRIMARY KEY
total_value          DECIMAL(18,4)
cash_balance         DECIMAL(18,4)
gross_exposure       DECIMAL(18,4)
net_exposure         DECIMAL(18,4)
daily_cumulative_pnl DECIMAL(18,4)
open_positions       INTEGER
snapshot_at          TIMESTAMPTZ NOT NULL
```

The entity exists and is wired to JPA, but nothing writes to it in phase 1 — portfolio summaries are computed on demand from `positions` + `fills` aggregations.

---

## Inbound Event Contract

### `orders.lifecycle` — produced by the Execution Engine

Payload schema (`OrderLifecycleEvent`):

```json
{
  "orderId": "8f5e...",
  "status": "PARTIALLY_FILLED",
  "order": {
    "orderId": "8f5e...",
    "clientOrderId": "alpha-vwap-0001",
    "symbol": "AAPL",
    "side": "BUY",
    "quantity": 100,
    "orderType": "LIMIT",
    "limitPrice": 150.25,
    "strategyName": "VWAP",
    "filledQuantity": 10,
    "avgFillPrice": 150.25,
    "exchangeOrderId": "BROKER-ABC-42",
    "venue": "SIMULATED"
  },
  "fill": {
    "fillId": "f0c1...",
    "orderId": "8f5e...",
    "exchangeFillId": "BROKER-F-78",
    "symbol": "AAPL",
    "side": "BUY",
    "fillPrice": 150.25,
    "fillQuantity": 10,
    "commission": 0.05,
    "venue": "SIMULATED",
    "filledAt": "2026-04-19T14:30:22.123Z"
  },
  "reason": null,
  "timestamp": "2026-04-19T14:30:22.129Z"
}
```

Fills are **optional**: status-only transitions (`NEW → SUBMITTED`, `SUBMITTED → CANCELLED`, `SUBMITTED → REJECTED`) arrive with `fill: null`. Every partial or full fill carries a populated `fill`.

### `market-data.ticks` — produced by the Market Data Gateway

Payload schema (`MarketTickEvent`): symbol, timestamp, event type (`TRADE` / `QUOTE` / `BAR`), price (trade) or bid/ask (quote), size, cumulative volume, source, and a `stale` flag. Stale ticks are dropped at the consumer.

---

## Outbound Event Contract

### `positions.updates` — produced by the Order Manager

Fired **once per successfully persisted fill**, keyed by symbol (guaranteeing per-symbol ordering when consumers use a single partition key):

```json
{
  "symbol": "AAPL",
  "netQuantity": 110,
  "avgEntryPrice": 150.25,
  "realizedPnl": -0.05,
  "unrealizedPnl": 55.00,
  "lastMarkPrice": 150.75,
  "timestamp": "2026-04-19T14:30:22.345Z"
}
```

Status-only lifecycle events (no fill) do **not** produce a position update — the position hasn't changed.

---

## The Two Consumers

### `OrderLifecycleConsumer`

One `@KafkaListener` on `orders.lifecycle`. For every message it:

1. Parses the JSON via Jackson (`JavaTimeModule` registered for `Instant` fields).
2. Dispatches to `handle(OrderLifecycleEvent)` inside a single `@Transactional` boundary.
3. On exceptions, logs partition+offset+message and swallows — **at-least-once** delivery relies on offsets only advancing after successful processing, and a single bad message should not poison the consumer. (Dead-letter queue routing is planned for phase 2.)

Inside `handle()`:

```
event.order == null                      → drop (log warn)
event.order present                      → upsertOrder(event)
event.fill == null                       → done (no position change)
event.fill present, already persisted    → drop (dedup)
event.fill persisted fresh               → applyFill() + publish positions.updates
```

### `MarketDataConsumer`

One `@KafkaListener` on `market-data.ticks`. For every tick:

- If `stale = true` → skip.
- If the event has a `price` field (TRADE) → use it.
- Else if `bidPrice` and `askPrice` both present (QUOTE) → use the midpoint.
- Else → skip (no usable price).
- Update the in-memory `ConcurrentHashMap<String, BigDecimal>` mark-price cache inside `PositionService`.

The consumer **does not touch the database** on every tick — thousands of ticks per second would kill the DB. Instead, a `@Scheduled(fixedDelayString = "${order-manager.mark-to-market.interval-ms:1000}")` method reads the cache and writes `positions.last_mark_price` + `positions.unrealized_pnl` for open positions once per second.

---

## The Three Services

### `OrderPersistenceService` — idempotent writes

Two methods, both `@Transactional`:

**`upsertOrder(OrderLifecycleEvent)`**
- Looks up the order by `client_order_id` (the idempotency key — unique index enforced by schema).
- If absent → insert a new `OrderEntity` with fields copied from `event.order`.
- If present → check the state transition against `OrderStatus.canTransitionTo()`. Illegal transitions (e.g. a late `SUBMITTED` after `FILLED`) are logged and dropped; the row is not touched.
- Legal transitions → update status, `filled_quantity`, `avg_fill_price`, `exchange_order_id`, `venue`. The JPA `@PreUpdate` hook bumps `updated_at`.
- Records the `mariaalpha.orders.persisted.total{side}` counter on first sight and the `mariaalpha.orders.persist.duration.ms` histogram always.

**`persistFillIfAbsent(OrderEntity, FillEvent)`**
- If `fill.exchangeFillId` is set and `fills.exchange_fill_id` already contains it → return empty (duplicate).
- Otherwise insert a new `FillEntity` with commission defaulting to zero.
- Fires the `mariaalpha.fills.persisted.total{side,venue}` counter.

The two-layer idempotency (client_order_id for orders, exchange_fill_id for fills) means the consumer is safe to reprocess arbitrary replays of `orders.lifecycle` — a common scenario after a rolling restart with `earliest` auto-offset.

### `PositionService` — the P&L engine

This is the most subtle component. On every persisted fill, `applyFill(FillEntity)` runs inside a transaction and:

1. Acquires a **pessimistic write lock** on the `PositionEntity` row for the symbol (`@Lock(PESSIMISTIC_WRITE)`). If the row doesn't exist, it's inserted lazily. The lock prevents two concurrent fills for the same symbol from stomping on each other.
2. Computes the signed fill quantity: `+qty` for BUY, `-qty` for SELL.
3. Branches on the relationship between `currentSign = netQuantity.signum()` and `fillSign = signedFillQty.signum()`:

   **Case A — Opening from flat** (`currentSign == 0`):
   - `netQuantity = signedFillQty`
   - `avgEntryPrice = fillPrice`
   - Realized P&L: unchanged.

   **Case B — Adding to existing position** (`currentSign == fillSign`):
   - New quantity = `currentQty + signedFillQty`.
   - New avg entry = weighted average:
     ```
     avgEntryPrice = (currentAvg × |currentQty| + fillPrice × |signedFillQty|) / |newQty|
     ```
   - Realized P&L: unchanged.

   **Case C — Reducing or flipping** (`currentSign != fillSign`):
   - Closing qty = `min(|signedFillQty|, |currentQty|)` — the portion that unwinds the existing position.
   - Realized per unit = `(fillPrice - currentAvg) × currentSign` (positive if closing a long above cost or closing a short below cost).
   - `realizedPnl += closingQty × realizedPerUnit`.
   - New quantity = `currentQty + signedFillQty`.
   - If `newQty == 0` → position flat, `avgEntryPrice = 0`.
   - If sign flipped (fill exceeded existing position) → new `avgEntryPrice = fillPrice`, `netQuantity` is the residual short/long.

4. Subtract commission: `realizedPnl -= commission`.
5. Recompute unrealized P&L using the cached mark price (fall back to `fillPrice` if no tick has arrived):
   ```
   unrealizedPnl = (markPrice - avgEntryPrice) × netQuantity
   ```
   This formula is sign-correct for shorts: if `netQuantity` is negative and `markPrice < avgEntryPrice`, the product is positive (profit on a short cover below cost).
6. Save. The `@PreUpdate` hook stamps `updated_at`.

All arithmetic uses `BigDecimal` with scale 8 and `HALF_UP` rounding, matching the schema's `DECIMAL(18,8)`. There is no floating-point anywhere in the P&L path.

### `PortfolioService` — on-demand aggregations

One public entry point: `summary()`. Returns a `PortfolioSummaryResponse` with:

- `totalValue = cashBalance + netExposure + unrealizedPnl`
- `cashBalance = initialCash + Σ(SELL: +price × qty) − Σ(BUY: +price × qty) − Σ(commission)` — computed by scanning every fill.
- `grossExposure = Σ |netQuantity × markPrice|` over open positions (uses `avgEntryPrice` as a fallback when no mark is cached).
- `netExposure = Σ (netQuantity × markPrice)` — signed.
- `realizedPnl = Σ positions.realizedPnl`.
- `unrealizedPnl = Σ positions.unrealizedPnl`.
- `totalPnl = realizedPnl + unrealizedPnl`.
- `openPositions` = count of positions with non-zero quantity.
- `asOf` = `Instant.now()`.

The initial cash is configurable via `order-manager.portfolio.initial-cash` (default `$1,000,000`). Phase 1 does not maintain a running cash column — every summary request re-aggregates over the entire fill history. This is fine at phase-1 volumes (tens of thousands of fills) but is the obvious optimisation target when fill counts grow: either a rolling cash column on `positions`, or a materialized view, or write-time maintenance.

---

## State Machine

`OrderStatus` enforces a directed graph of legal transitions, matching the Execution Engine's state machine:

```
         ┌────────────┐
         │    NEW     │
         └──┬──┬──┬───┘
            │  │  │
      ┌─────┘  │  └──────┐
      ▼        ▼         ▼
 SUBMITTED  REJECTED  CANCELLED
   │ │ │ │
   │ │ │ └──► REJECTED
   │ │ └────► CANCELLED
   │ └──────► FILLED    (terminal)
   └────────► PARTIALLY_FILLED ─┐
                   │ │ │ ▲      │
                   │ │ │ └──────┘  (self-loop: more partials)
                   │ │ └─► CANCELLED
                   │ └───► FILLED
```

Terminal states (`FILLED`, `CANCELLED`, `REJECTED`) have no outbound edges — `canTransitionTo(anything)` returns false. This stops out-of-order replays from regressing a completed order.

The check in `OrderPersistenceService.upsertOrder()` is intentionally lenient for idempotent replays: a message that would produce the same current status (e.g. re-receiving the same `PARTIALLY_FILLED` event) is allowed and applied (it may contain a newer `filled_quantity` from a later partial).

---

## REST API

All five endpoints live under `/api/*`. They are read-only; the Order Manager offers no mutating routes in phase 1.

### `GET /api/orders`

Query parameters:
- `symbol` (optional) — filter by ticker.
- `status` (optional) — one of `NEW`, `SUBMITTED`, `PARTIALLY_FILLED`, `FILLED`, `CANCELLED`, `REJECTED`.
- `strategy` (optional) — filter by originating strategy name.
- `from`, `to` (optional, ISO-8601 `Instant`) — filter by `created_at` window. `from > to` yields a 400.
- `limit` (optional, default 100, max 500) — clamped server-side.

Returns `List<OrderResponse>` ordered by `created_at DESC`. Fills are **not** included in the list view; fetch `/api/orders/{id}` for those.

### `GET /api/orders/{orderId}`

Returns a single `OrderResponse` with a `fills[]` array (chronological ascending). 404 if the UUID is not found.

### `GET /api/positions`

Returns every row in `positions` — including flat positions where `net_quantity = 0`. Flat positions are useful for historical-strategy debugging and don't hurt response size. Clients that only care about open positions can filter client-side, or we can add a `?open=true` query in phase 2.

### `GET /api/positions/{symbol}`

Returns a single `PositionResponse`. 404 if the symbol has never had a position row created.

### `GET /api/portfolio/summary`

Returns the aggregated `PortfolioSummaryResponse` described above. Always 200 — an empty portfolio returns zeros plus the initial cash.

---

## Idempotency and Delivery Semantics

The Order Manager assumes **at-least-once** delivery from both Kafka topics. The guarantees it offers in return:

| Scenario | Guarantee | Mechanism |
|----------|-----------|-----------|
| Consumer restart | No duplicate orders in DB | `orders.client_order_id` UNIQUE |
| Consumer restart | No duplicate fills in DB | `fills.exchange_fill_id` + `existsByExchangeFillId` check |
| Replay from earliest | Positions converge to correct state | All P&L math is idempotent per unique fill |
| Concurrent fills, same symbol | No lost updates | `PESSIMISTIC_WRITE` lock on position row |
| Out-of-order lifecycle events | Terminal-state protection | `OrderStatus.canTransitionTo()` gate |
| Illegal transitions | Dropped with warning log | Same gate |

The **replay-safe** property is important. We can wipe the database, reset the consumer group offset to 0 on `orders.lifecycle`, and reconstruct the entire order history. Positions will converge to the same values (modulo intra-day mark-to-market unrealized P&L, which depends on the live tick stream).

---

## Concurrency Model

Three concurrency surfaces:

1. **Kafka consumer threads.** Spring Kafka runs each listener container with a configurable concurrency (default 1 per topic partition). With a single partition per topic in local dev, this means one thread per consumer. Production with partitioned topics → one thread per partition.

2. **Position update contention.** If two lifecycle events for the same symbol arrive concurrently (one from a BUY fill, one from a SELL fill on a different order), the pessimistic write lock on `positions` serializes them. The second transaction blocks on the first's `SELECT ... FOR UPDATE` until commit.

3. **Mark-price cache.** A `ConcurrentHashMap<String, BigDecimal>` inside `PositionService`. Writes come from `MarketDataConsumer`; reads come from both the scheduled mark-to-market job and every `applyFill()` call. No explicit synchronisation is needed — `BigDecimal` is immutable and `ConcurrentHashMap.put/get` are safe.

The `@Scheduled` mark-to-market job is safe to run concurrently with fill processing because it only writes `last_mark_price` and `unrealized_pnl` — the core position state (`net_quantity`, `avg_entry_price`, `realized_pnl`) is never touched by mark-to-market. In the worst case, a fill and a mark-to-market update race and the fill "wins" (JPA dirty-checking writes the more recent version); either value is correct, and the next mark-to-market sweep (1s later) reconciles.

---

## Configuration

All configuration lives in `application.yml` or environment variables (`POSTGRES_*`, `KAFKA_BOOTSTRAP_SERVERS`, `MANAGEMENT_PORT`, `SPRING_PROFILES_ACTIVE`).

| Property | Default | Purpose |
|----------|---------|---------|
| `server.port` | 8086 | REST API port |
| `management.server.port` | 8087 | Actuator port |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/mariaalpha` | Postgres connection |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Kafka cluster |
| `spring.kafka.consumer.group-id` | `order-manager` | Consumer group |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | Replay from start on new group |
| `order-manager.kafka.orders-lifecycle-topic` | `orders.lifecycle` | Inbound order events |
| `order-manager.kafka.market-data-topic` | `market-data.ticks` | Inbound market data |
| `order-manager.kafka.positions-updates-topic` | `positions.updates` | Outbound position events |
| `order-manager.portfolio.initial-cash` | `1000000.00` | Starting cash balance |
| `order-manager.mark-to-market.interval-ms` | `1000` | Mark-to-market frequency |

Two `@ConfigurationProperties` records hold the domain-specific config:

- `KafkaConfig` (`order-manager.kafka.*`) — topic names only.
- `PortfolioConfig` (`order-manager.portfolio.*`) — `initialCash`.

Both are injected by constructor into the services that need them.

---

## Observability

### Metrics (Micrometer → Prometheus)

Registered in `OrderManagerMetrics`:

| Name | Type | Labels | Meaning |
|------|------|--------|---------|
| `mariaalpha.orders.persisted.total` | Counter | `side` | Distinct orders persisted (incremented only on first sight per `client_order_id`) |
| `mariaalpha.fills.persisted.total` | Counter | `side`, `venue` | Fills persisted, by side and execution venue |
| `mariaalpha.orders.persist.duration.ms` | Timer (histogram) | — | Order upsert latency (p50/p95/p99 exposed) |
| `mariaalpha.positions.count` | Gauge | — | Total position rows (including flat) |
| `mariaalpha.portfolio.total.pnl` | Gauge | — | Σ realized + unrealized across all positions |
| `mariaalpha.portfolio.gross.exposure` | Gauge | — | Σ |netQuantity × markPrice| over open positions |
| `mariaalpha.portfolio.net.exposure` | Gauge | — | Σ (netQuantity × markPrice) — signed |

Gauges poll their source (repository or service) on each scrape — no background thread maintains them.

All meters carry the `application=order-manager` tag globally, added by `management.metrics.tags`.

### Health

Spring Boot actuator exposes three probes:

- `/actuator/health/liveness` — returns `UP` as long as the JVM is running.
- `/actuator/health/readiness` — `UP` when `readinessState` and the `db` component are both healthy. This is what Kubernetes uses to gate traffic.
- `/actuator/health` — full drill-down, including `kafka` (connected consumer groups + broker reachability).

### Logs

Structured via SLF4J / Logback (inherited from `spring-boot-starter`). Notable log events:

- `WARN` — illegal state transition dropped; lifecycle event with null order snapshot; duplicate fill skipped (DEBUG in production, WARN in dev).
- `ERROR` — Kafka deserialization failure (message kept, consumer advances).
- `DEBUG` — per-fill position update (`Position AAPL after fill: qty=110 avg=150.25 realized=-0.05 unrealized=55.00`).

---

## Testing Strategy

Four classes of tests, each with a different fidelity/speed tradeoff:

| Class | Containers | Count | When to run |
|-------|------------|-------|-------------|
| Unit tests (mockito) | none | 59 | Every save / commit — ~1s |
| `@DataJpaTest` + Testcontainers Postgres | Postgres | 17 | CI and before any DB-touching merge — ~10s |
| `@WebMvcTest` | none | 13 | Part of unit suite |
| `@SpringBootTest` + Testcontainers (Postgres + Kafka) | both | 3 | CI only; end-to-end confidence — ~15 min |

The E2E test (`OrderLifecycleConsumerIntegrationTest`) publishes a real three-message sequence (`NEW → SUBMITTED → PARTIALLY_FILLED` with a fill) to a real Kafka broker, waits for the Order Manager's consumer to pick it up, and asserts:
1. The order row is persisted with the final status and filled quantity.
2. The fill row is persisted with the exchange_fill_id.
3. The position row shows the correct net quantity and avg entry price.
4. A message is published to `positions.updates` keyed by symbol and containing the expected JSON fields.
5. A duplicate fill (same `exchangeFillId`) is deduplicated — the fill row is not re-inserted.

Total coverage across the module: 92 tests.

---

## Walk-through: a complete BUY → SELL round trip

Suppose a VWAP strategy emits a signal to buy 100 shares of AAPL, and the execution completes across two partial fills.

### T+0: signal hits the Execution Engine

Not the Order Manager's concern. The Execution Engine risk-checks, routes, and submits.

### T+1: first partial fill

The Execution Engine publishes:
```json
{
  "orderId": "8f5e...", "status": "PARTIALLY_FILLED",
  "order": { "clientOrderId": "vwap-001", "symbol": "AAPL", "side": "BUY",
             "quantity": 100, "filledQuantity": 40, "avgFillPrice": 150.00, ... },
  "fill": { "fillId": "f0c1...", "exchangeFillId": "EX-F-1001",
            "fillPrice": 150.00, "fillQuantity": 40, "commission": 0.02, ... }
}
```

`OrderLifecycleConsumer.handle()`:
1. `upsertOrder`: no row for `client_order_id='vwap-001'` → insert new `orders` row with status `PARTIALLY_FILLED`.
2. `persistFillIfAbsent`: no row for `exchange_fill_id='EX-F-1001'` → insert new `fills` row.
3. `applyFill`:
   - Lock `positions(symbol='AAPL')` → doesn't exist, insert flat.
   - `currentSign=0`, so Case A: `netQuantity=+40`, `avgEntryPrice=150.00`.
   - `realizedPnl = 0 - 0.02 = -0.02` (commission hit).
   - No cached mark yet → use fillPrice as mark: `lastMarkPrice=150.00`, `unrealizedPnl=0`.
4. `publisher.publish()`: send to `positions.updates` keyed `"AAPL"`.

DB state:
- `orders`: 1 row, status=PARTIALLY_FILLED, filled_quantity=40.
- `fills`: 1 row.
- `positions`: `AAPL qty=40 avg=150.00 realized=-0.02 unrealized=0`.

### T+2: tick arrives

`MarketDataConsumer` receives `{symbol:"AAPL", price:151.00, eventType:"TRADE"}`. Updates mark-price cache: `AAPL → 151.00`.

### T+3: scheduled mark-to-market sweep (1s after previous sweep)

`markToMarket()` reads open positions. For AAPL:
- `lastMarkPrice = 151.00`
- `unrealizedPnl = (151.00 - 150.00) × 40 = 40.00`
- Save. DB updated.

### T+4: second partial fill

Execution Engine publishes another `PARTIALLY_FILLED` event with a new fill:
```json
"fill": { "exchangeFillId": "EX-F-1002", "fillPrice": 150.50, "fillQuantity": 60, "commission": 0.03 }
```

`applyFill`:
- Lock `positions('AAPL')` → found, qty=+40.
- `currentSign=+1, fillSign=+1` → Case B (adding to long).
- `newQty = 40 + 60 = 100`.
- `avgEntryPrice = (150.00 × 40 + 150.50 × 60) / 100 = 150.30`.
- `realizedPnl = -0.02 - 0.03 = -0.05`.
- Mark is 151.00 → `unrealizedPnl = (151.00 - 150.30) × 100 = 70.00`.
- Save. Publish to `positions.updates`.

The order has now been `FILLED` in the Execution Engine's view — the next lifecycle event transitions status to `FILLED` (no fill attached).

### T+10: strategy decides to exit

Execution Engine submits a SELL-100, gets a fill at 152.00:
```json
"fill": { "exchangeFillId": "EX-F-2001", "fillPrice": 152.00, "fillQuantity": 100, "commission": 0.05 }
```

`applyFill`:
- Lock `positions('AAPL')` → qty=+100, avg=150.30.
- `currentSign=+1, fillSign=-1` → Case C (reducing).
- `closingQty = min(100, 100) = 100`.
- `realizedPerUnit = (152.00 - 150.30) × 1 = 1.70`.
- `realizedPnl += 100 × 1.70 = 170.00`. Plus commission: `realizedPnl = -0.05 + 170.00 - 0.05 = 169.90`.
- `newQty = 0` → flat → `avgEntryPrice = 0`.
- `unrealizedPnl = 0` (flat).
- Save. Publish.

### Final state

- `orders`: 2 rows (the BUY order and the SELL order), both `FILLED`.
- `fills`: 3 rows, all with distinct `exchange_fill_id`.
- `positions`: 1 row for AAPL, `qty=0 avg=0 realized=169.90 unrealized=0`.
- Three messages on `positions.updates`.
- `GET /api/portfolio/summary` returns:
  - `cashBalance = 1,000,000 - (150.00 × 40) - (150.50 × 60) + (152.00 × 100) - 0.10 (sum commissions) = 1,000,169.90`
  - `realizedPnl = 169.90`
  - `totalPnl = 169.90`

A sanity check: cash delta ($169.90) = realized P&L. This is the invariant the portfolio math preserves — when there are no open positions, `cashBalance - initialCash == realizedPnl`.

---

## Deployment

### Local (docker-compose)

The `order-manager` service in `docker-compose.yml` depends on `postgres` (for persistence) and `kafka` (for both inbound topics). Bring up the whole pipeline with:

```bash
docker compose up -d postgres kafka
docker compose build order-manager
docker compose up -d order-manager
```

Health-check: `curl http://localhost:8087/actuator/health/readiness` must return `{"status":"UP"}` before routing traffic.

### Kubernetes (planned, phase 2)

Per TDD §10.2: HPA 1–2 replicas, 250m request / 500m limit CPU, 256Mi request / 512Mi limit memory. The consumer group `order-manager` provides horizontal scale — adding a replica automatically picks up one of the `orders.lifecycle` partitions once we repartition the topic (phase 2, currently single-partition in dev).

A non-obvious operational consideration: **positions cannot be sharded**. Two replicas of the Order Manager both see every fill (Kafka delivers to exactly one consumer per group partition, but a two-partition topic with two replicas means each handles half the symbols). That's fine — position updates are keyed by symbol and each symbol only ever lives on one partition. What *cannot* work is running two replicas on the same partition — they'd fight over the pessimistic lock and duplicate work.

---

## What's Out of Scope (Phase 2+)

- **Scheduled portfolio snapshot writer** — the `portfolio_snapshots` table exists but no job populates it. Planned as an hourly `@Scheduled` that writes a row.
- **Daily P&L attribution** — the current `realizedPnl` is lifetime. Per-day breakdown needs either a `fills` scan with date bucketing or a dedicated `daily_pnl` table.
- **Sector and beta population on `positions`** — schema columns exist; no source wired up. Candidates: an external reference-data service, or a static CSV bootstrap.
- **Write-side API** — creating, amending, cancelling orders from an operator UI. The Execution Engine owns that surface; the Order Manager would forward requests.
- **Auth on REST endpoints** — currently open. Phase 2 adds JWT / mTLS at the API Gateway layer.
- **Broker reconciliation** — end-of-day compare between Order Manager positions and the broker's position report, with alerting on drift.
- **Running cash column** — eliminate the full fills scan on every summary request by maintaining `positions.cash_balance` incrementally (or a `portfolio_state` singleton row).
- **DLQ routing** — malformed Kafka messages are logged and skipped today. A dead-letter topic allows post-incident forensics.

---

## References

- **TDD:** [docs/technical-design-document.md](technical-design-document.md) — §3.5 (FR-25..28), §5.2.5, §5.4 (ERD), §7.3 (idempotency), §8.2 (metrics), §10.2 (K8s sizing).
- **Plan:** `local/plan-1.6.1-1.6.5-order-manager.md` — the implementation plan this module was built from.
- **Sibling explainers:** [docs/execution-engine-explainer.md](execution-engine-explainer.md), [docs/vwap-strategy-explainer.md](vwap-strategy-explainer.md), [docs/ml-signal-service-explainer.md](ml-signal-service-explainer.md).
- **Source root:** [order-manager/src/main/java/com/mariaalpha/ordermanager/](../order-manager/src/main/java/com/mariaalpha/ordermanager/).
- **Liquibase changesets:** [order-manager/src/main/resources/db/changelog/changesets/](../order-manager/src/main/resources/db/changelog/changesets/).
