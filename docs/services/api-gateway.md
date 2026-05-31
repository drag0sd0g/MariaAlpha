# API Gateway — How It Works

## Overview

The API Gateway is a Spring Boot 3 / Spring Cloud Gateway (WebFlux) service that sits at the
perimeter of the MariaAlpha system. It is the **single ingress point** for all external traffic —
browser REST calls, WebSocket connections, and future third-party integrations. Nothing outside the
Docker network reaches any downstream service directly.

It has four distinct responsibilities:

1. **Authentication** — validates the shared API key on every request before any backend connection
   is opened.
2. **HTTP routing** — proxies REST requests to the correct downstream microservice based on path.
3. **WebSocket streaming** — lifts Kafka topic messages into persistent WebSocket sessions, with
   optional per-field filtering.
4. **Health aggregation** — exposes Kubernetes-compatible readiness and liveness probes that reflect
   the health of all downstream services.

| Port | Purpose |
|------|---------|
| 8080 | Public — REST proxy, WebSocket endpoints, OpenAPI docs |
| 8091 | Management — `/actuator/health`, `/actuator/prometheus`, `/actuator/metrics` |

---

## Request Flow

```
Client (browser / curl)
        │
        ▼
  ┌─────────────────────────────────────────┐
  │         ApiKeyAuthenticationFilter      │  order -100 — runs first
  │  • Extract key from header or query param│
  │  • Constant-time compare                │
  │  • Return 401 JSON if invalid           │
  └───────────────┬─────────────────────────┘
                  │ (authenticated)
        ┌─────────┴──────────────┐
        ▼                        ▼
  /ws/** paths            /api/** paths
        │                        │
        ▼                        ▼
 SimpleUrlHandlerMapping   RoutePredicateHandlerMapping
 (WebSocket upgrade)       (Spring Cloud Gateway routes)
        │                        │
        ▼                        ▼
 StreamingWebSocketHandler  Downstream microservice
 (Kafka → WS bridge)       (reverse proxy via WebFlux)
```

The filter is a `WebFilter`, not a Spring Cloud Gateway `GlobalFilter`. This distinction is
critical: `GlobalFilter` only runs for routes resolved by `RoutePredicateHandlerMapping`, which
means WebSocket upgrade handshakes served by `SimpleUrlHandlerMapping` would bypass it entirely.
`WebFilter` intercepts every inbound HTTP request, including upgrade GETs.

---

## Authentication

### `ApiKeyAuthenticationFilter`

Runs at order `-100` — before any handler mapping, so no backend connection is ever opened for
an unauthenticated request.

**Key extraction** (header preferred over query parameter):
1. Reads the `X-API-Key` header.
2. If absent, reads the `apiKey` query parameter.
3. If both absent, rejects with 401.

The header-vs-query duality exists because browser WebSocket APIs cannot set custom headers — the
API key must travel as a query parameter for WebSocket connections.

**Excluded paths** skip authentication entirely (Ant-pattern matching):

```
/actuator/**     — Kubernetes probes and Prometheus scraping
/v3/api-docs/**  — OpenAPI spec
/swagger-ui/**   — Swagger UI assets
/webjars/**      — Swagger static assets
/favicon.ico     — browser auto-request
/                — root path
```

**Error responses** are JSON with a `WWW-Authenticate: ApiKey` header:

```json
{"status":401,"error":"Unauthorized","detail":"Missing or invalid API key"}
```

If `MARIAALPHA_API_KEY` is not set at all, a distinct message is returned:

```json
{"status":401,"error":"Unauthorized","detail":"Gateway API key is not configured"}
```

### `ApiKeyMatcher`

Constant-time comparison using `MessageDigest.isEqual()`. Unlike `String.equals()`, this does not
short-circuit on the first mismatched byte, preventing timing-based key inference. Both null and
blank inputs always return false so callers do not need to null-check.

### `ApiKeyRedactingTurboFilter`

A Logback `TurboFilter` installed globally via `logback-spring.xml`. It intercepts every log event
**before** any appender receives it and replaces API key values with `***`:

- `apiKey=<value>` in query strings (case-sensitive)
- `X-API-Key: <value>` in header dumps (case-insensitive)

This is defense-in-depth: even if a future code change accidentally logs a request URI or header
map, the key is redacted before it reaches any log sink.

---

## HTTP Routing

Routes are defined in two places that together cover all downstream services:

### YAML routes (`application.yml`)

Five routes defined declaratively. Spring Cloud Gateway evaluates path predicates in order:

| Route ID | Paths | Downstream |
|----------|-------|-----------|
| `strategies` | `/api/strategies/**`, `/api/rfq/**` | `STRATEGY_ENGINE_URL` (default: `localhost:8082`) |
| `orders` | `/api/orders/**`, `/api/positions/**`, `/api/portfolio/**` | `ORDER_MANAGER_URL` (default: `localhost:8086`) |
| `execution` | `/api/execution/**`, `/api/routing/**` | `EXECUTION_ENGINE_URL` (default: `localhost:8084`) |
| `post-trade` | `/api/tca/**`, `/api/recon/**` | `POST_TRADE_URL` (default: `localhost:8088`) |
| `market-data` | `/api/market-data/**` | `MARKET_DATA_GATEWAY_URL` (default: `localhost:8079`) |

Paths are forwarded as-is. The HTTP client uses a 2-second connect timeout and 10-second response
timeout.

### Java route (`RouteConfiguration`)

One route defined programmatically because it requires a **path rewrite** filter:

| Route ID | Path | Downstream | Path rewrite |
|----------|------|-----------|-------------|
| `analytics` | `/api/analytics/**` | `ANALYTICS_SERVICE_URL` (default: `localhost:8095`) | `/api/analytics/foo` → `/v1/analytics/foo` |

The analytics service exposes its API under `/v1/` rather than `/api/`, so the gateway rewrites the
prefix before forwarding.

### Complete public path map

```
/api/strategies/**    →  strategy-engine:8082
/api/rfq/**           →  strategy-engine:8082
/api/orders/**        →  order-manager:8086
/api/positions/**     →  order-manager:8086
/api/portfolio/**     →  order-manager:8086
/api/execution/**     →  execution-engine:8084
/api/routing/**       →  execution-engine:8084
/api/tca/**           →  post-trade:8088
/api/recon/**         →  post-trade:8088
/api/market-data/**   →  market-data-gateway:8079
/api/analytics/**     →  analytics-service:8095 (rewritten to /v1/analytics/**)
/ws/market-data       →  KafkaTopicBroadcaster (market-data.ticks)
/ws/positions         →  KafkaTopicBroadcaster (positions.updates)
/ws/orders            →  KafkaTopicBroadcaster (orders.lifecycle)
/ws/alerts            →  KafkaTopicBroadcaster (analytics.risk-alerts)
```

---

## WebSocket Streaming

Real-time data is delivered to clients over WebSocket. The pipeline is:

```
Kafka topic
    │
    ▼
KafkaTopicBroadcaster          (one Sinks.Many per topic, multicast)
    │
    ├──► Flux<String> subscriber 1 (client A, no filter)
    ├──► Flux<String> subscriber 2 (client B, filter: symbol=AAPL)
    └──► Flux<String> subscriber 3 (client C, filter: symbol=MSFT)
```

### `KafkaTopicBroadcaster`

On startup (`@PostConstruct`) creates one `Sinks.Many<String>` per configured endpoint. Each sink
is a multicast sink with a backpressure buffer of 1024 messages.

Four `@KafkaListener` methods consume from one topic each:

| Listener method | Topic | Consumer group |
|----------------|-------|----------------|
| `onMarketData` | `market-data.ticks` | `api-gateway-<uuid>-market-data` |
| `onPositions` | `positions.updates` | `api-gateway-<uuid>-positions` |
| `onOrders` | `orders.lifecycle` | `api-gateway-<uuid>-orders` |
| `onAlerts` | `analytics.risk-alerts` | `api-gateway-<uuid>-alerts` |

Consumer group IDs use `${random.uuid}` so every gateway instance gets a unique group. This means
**each instance receives all messages** rather than load-balancing them — appropriate for fan-out
to connected WebSocket clients. `auto.offset.reset=latest` means a freshly started gateway only
delivers new messages; it does not replay historical events to newly connecting clients.

On shutdown (`@PreDestroy`) all sinks are completed, which causes all active WebSocket sessions to
close cleanly.

### `StreamingWebSocketHandler`

One handler instance per configured WebSocket endpoint. On each new client connection:

1. Extracts the optional filter value from the handshake URL query string using a hand-rolled
   parser (avoids Spring's `UriComponents` in the hot path).
2. Gets the topic's `Flux<String>` from the broadcaster.
3. If a filter value is present, applies `filter(json → fieldValue.equals(extract(json, filterKey)))`.
4. Applies `onBackpressureLatest()` — if the client's TCP receive window fills, older messages are
   dropped and only the most recent is kept. This prevents a slow client from stalling the Reactor
   pipeline.
5. Maps each JSON string to a `WebSocketMessage` and sends it to the session.

**Filter examples:**

```
/ws/market-data?apiKey=<key>              — all symbols
/ws/market-data?apiKey=<key>&symbol=AAPL  — AAPL ticks only
/ws/orders?apiKey=<key>&orderId=<uuid>    — one order's events
/ws/alerts?apiKey=<key>&symbol=AAPL       — AAPL risk alerts only
```

The `filterKey` per endpoint (configured in `application.yml`):

| Endpoint | Kafka topic | Filter key |
|----------|-------------|-----------|
| `/ws/market-data` | `market-data.ticks` | `symbol` |
| `/ws/positions` | `positions.updates` | `symbol` |
| `/ws/orders` | `orders.lifecycle` | `orderId` |
| `/ws/alerts` | `analytics.risk-alerts` | `symbol` |

### `SymbolKeyExtractor`

Extracts the value of a named top-level JSON field using Jackson's streaming `JsonParser`. This
avoids full JSON deserialization on the hot path (every Kafka message, for every connected client
that has a filter). Returns null on missing field, malformed JSON, or null input — the filter
predicate then drops the message.

### WebSocket auth note

Authentication for WebSocket connections uses the `apiKey` query parameter because browsers cannot
set the `Authorization` or `X-API-Key` headers on a WebSocket handshake. The
`ApiKeyAuthenticationFilter` checks both the header and the query parameter, so the same auth logic
covers both REST and WebSocket connections.

---

## Health Monitoring

### Downstream health checking

`DownstreamHealthChecker` probes each configured downstream's `/actuator/health` endpoint via a
dedicated `WebClient` with a 500ms timeout. Results are cached in a Caffeine `AsyncCache` with a
5-second TTL.

**Why cache?** Kubernetes readiness probes run at ~10 Hz. With 5 required downstreams, uncached
probes would generate ~50 outbound health check requests per second from a single gateway
instance, purely from probe traffic. The 5-second TTL caps this at ~1 req/sec/downstream.

**Management URL vs. main URL**: Each downstream configures a separate `managementUrl` pointing to
its actuator port. This decouples the health check path from the request-serving path — useful when
the main port is temporarily overloaded but the service is otherwise healthy.

### `AggregateDownstreamHealthIndicator`

Registered with Spring Boot Actuator as the `"downstreams"` health indicator. On each health
query:

1. Checks all configured downstreams in parallel via `Flux.flatMap`.
2. Aggregates results:
   - Any **required** downstream is DOWN → `OUT_OF_SERVICE` (fails readiness probe)
   - All required downstreams are UP → `UP` (optional ones can be down)
3. Returns per-downstream detail:

```json
{
  "status": "UP",
  "components": {
    "downstreams": {
      "status": "UP",
      "details": {
        "strategy-engine": {"status": "UP",   "required": true,  "detail": "UP"},
        "execution-engine": {"status": "UP",  "required": true,  "detail": "UP"},
        "order-manager":    {"status": "UP",  "required": true,  "detail": "UP"},
        "post-trade":       {"status": "UP",  "required": true,  "detail": "UP"},
        "analytics-service":{"status": "DOWN","required": false, "detail": "ConnectException"}
      }
    }
  }
}
```

### Probe groups

Two probe groups are configured on the management port:

| Probe | URL | Included indicators | Fails when |
|-------|-----|---------------------|-----------|
| Readiness | `/actuator/health/readiness` | `readinessState` + `downstreams` | Any required downstream is DOWN, or app not fully started |
| Liveness | `/actuator/health/liveness` | `livenessState` | JVM deadlock or fatal application state |

The Docker health check targets liveness (`/actuator/health/liveness`), keeping the container
alive while downstream services restart. Kubernetes readiness (`/readiness`) prevents the gateway
from receiving traffic until all required downstreams are healthy.

### Configured downstreams

| Service | Required | Default URLs |
|---------|----------|-------------|
| `strategy-engine` | true | `localhost:8082` / mgmt `localhost:8083` |
| `execution-engine` | true | `localhost:8084` / mgmt `localhost:8085` |
| `order-manager` | true | `localhost:8086` / mgmt `localhost:8087` |
| `post-trade` | true | `localhost:8088` / mgmt `localhost:8089` |
| `analytics-service` | **false** | `localhost:8095` (same for mgmt) |

`analytics-service` is optional because it is a Phase 2 component not yet deployed in the default
docker-compose stack. Marking it non-required prevents it from blocking readiness.

---

## Configuration Reference

All gateway-specific configuration lives under `mariaalpha.gateway.*` in `application.yml` and is
injected as typed records via `@ConfigurationPropertiesScan`.

### Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `MARIAALPHA_API_KEY` | **Yes** | Shared secret; docker-compose fails to start if unset |
| `KAFKA_BOOTSTRAP_SERVERS` | No | Default: `localhost:9092`; use `kafka:9094` inside Docker |
| `STRATEGY_ENGINE_URL` | No | Default: `http://localhost:8082` |
| `STRATEGY_ENGINE_MANAGEMENT_URL` | No | Default: `http://localhost:8083` |
| `EXECUTION_ENGINE_URL` | No | Default: `http://localhost:8084` |
| `EXECUTION_ENGINE_MANAGEMENT_URL` | No | Default: `http://localhost:8085` |
| `ORDER_MANAGER_URL` | No | Default: `http://localhost:8086` |
| `ORDER_MANAGER_MANAGEMENT_URL` | No | Default: `http://localhost:8087` |
| `POST_TRADE_URL` | No | Default: `http://localhost:8088` |
| `POST_TRADE_MANAGEMENT_URL` | No | Default: `http://localhost:8089` |
| `ANALYTICS_SERVICE_URL` | No | Default: `http://localhost:8095` |
| `MANAGEMENT_PORT` | No | Default: `8091` |

### `DownstreamServicesProperties`

```
mariaalpha.gateway.downstreams.<name>.url              — main service URL
mariaalpha.gateway.downstreams.<name>.management-url   — actuator URL (for health checks)
mariaalpha.gateway.downstreams.<name>.required         — fails readiness if DOWN
```

`healthUrl()` on the `Downstream` record appends `/actuator/health` to the management URL,
falling back to the main URL if `managementUrl` is blank.

### `SecurityProperties`

```
mariaalpha.gateway.security.api-key            — the shared secret
mariaalpha.gateway.security.header-name        — default: X-API-Key
mariaalpha.gateway.security.query-param-name   — default: apiKey
mariaalpha.gateway.security.excluded-paths     — AntPath patterns, default list above
```

### `WebSocketProperties`

```
mariaalpha.gateway.websocket.endpoints.<name>.path        — WS path (e.g. /ws/orders)
mariaalpha.gateway.websocket.endpoints.<name>.topic       — Kafka topic name
mariaalpha.gateway.websocket.endpoints.<name>.filter-key  — JSON field to filter on
mariaalpha.gateway.websocket.backpressure-buffer-size     — default: 1024
```

---

## Observability

### Metrics

Prometheus metrics are scraped from `http://<host>:8091/actuator/prometheus`. Key metrics include:

- Standard Spring Boot metrics: JVM, HTTP server request counts/latencies, GC
- Spring Cloud Gateway: `gateway_requests_seconds`, `gateway_routes_requested`
- Kafka consumer: `kafka_consumer_fetch_manager_records_consumed_total` per listener
- Custom tag `application=api-gateway` on all metrics for Grafana dashboard filtering

### Logging

All loggers under `com.mariaalpha.apigateway` and `org.springframework.cloud.gateway` log at INFO.
`reactor.netty` is set to WARN to suppress connection-level noise. The `ApiKeyRedactingTurboFilter`
ensures API keys never reach any appender.

### OpenAPI / Swagger

When running, the aggregated API schema is available at:

- `http://localhost:8080/v3/api-docs` — machine-readable OpenAPI JSON
- `http://localhost:8080/swagger-ui` — browser-navigable Swagger UI

Both paths are excluded from authentication so they are accessible without a key.

---

## Docker / Deployment

### docker-compose

The gateway depends on kafka (healthy) plus strategy-engine, execution-engine, order-manager,
post-trade, and market-data-gateway. The `execution-engine` dependency uses `service_started` rather
than `service_healthy` because the execution engine's health check includes market data availability,
which is not ready immediately on startup.

`MARIAALPHA_API_KEY` is required at startup (`?` suffix in docker-compose env syntax) — the stack
will not start without it.

The UI service (`ui:`) is defined but commented out in `docker-compose.yml`. During development the
Vite dev server runs on port 5173 outside Docker and proxies `/api` and `/ws` to `localhost:8080`.

### Multi-stage Dockerfile

**Builder stage** (`gradle:8.12-jdk21`):
- Copies the root Gradle wrapper and settings files
- Copies stub `build.gradle.kts` files for all sibling modules so Gradle's multi-project resolution
  succeeds without building the entire monorepo
- Copies the api-gateway source and runs `bootJar` with quality checks and tests disabled

**Runtime stage** (`eclipse-temurin:21-jre-jammy`):
- Minimal JRE image, no build tools
- Exposes ports 8080 and 8091
- Runs as the default user (non-root)

---

## Test Suite

Tests are organized into unit and integration layers. Integration tests are tagged `@Tag("integration")`
and rely on `MockWebServer` (OkHttp) for simulating downstream services.

| Test class | Layer | What it covers |
|-----------|-------|----------------|
| `ApiKeyMatcherTest` | unit | Constant-time comparison edge cases (null, blank, case, length difference) |
| `ApiKeyAuthenticationFilterTest` | unit | Header auth, query-param auth, excluded paths, unconfigured key |
| `ApiKeyAuthenticationFilterLoggingTest` | unit | Verifies Logback events never contain the API key value |
| `RouteConfigurationTest` | unit | All 6 route IDs registered in `RouteLocator` |
| `SymbolKeyExtractorTest` | unit | JSON field extraction: present, absent, malformed, nested |
| `KafkaTopicBroadcasterTest` | unit | Fan-out: single subscriber, multiple subscribers, unknown topic, topic isolation |
| `StreamingWebSocketHandlerTest` | unit | Handler wires broadcaster Flux into session send |
| `AggregateDownstreamHealthIndicatorTest` | unit | Aggregation: all UP, required DOWN, optional DOWN, empty config |
| `DownstreamHealthCheckerTest` | integration | HTTP 200→UP, HTTP 500→DOWN, timeout→DOWN, cache hit deduplication |
| `AggregateHealthIntegrationTest` | integration | Full Spring context: readiness endpoint with mocked downstreams |
| `ApiKeyEndToEndIntegrationTest` | integration | Full Spring context: 401 without key, 200 with header, 200 with query param, actuator open |
| `RouteIntegrationTest` | integration | Full Spring context: all 6 routes forward to correct mocked downstream |
| `WebSocketIntegrationTest` | integration | Real Kafka (TestContainers): tick propagation, auth rejection, symbol filter |

The WebSocket integration test uses TestContainers to spin up a real Kafka broker. It:
1. Publishes a tick for AAPL and a tick for TSLA to `market-data.ticks`.
2. Connects a WebSocket client with `?symbol=AAPL`.
3. Asserts only the AAPL tick is received (TSLA is dropped by the filter).
4. Asserts that a connection attempt without an API key is rejected before the upgrade completes.

---

## Key Design Decisions

**`WebFilter` not `GlobalFilter`**: Spring Cloud Gateway's `GlobalFilter` only intercepts routes
resolved by its own `RoutePredicateHandlerMapping`. WebSocket endpoints are served by a
`SimpleUrlHandlerMapping` registered at `HIGHEST_PRECEDENCE + 1`, which Gateway never sees. Using
`WebFilter` at order `-100` intercepts all requests regardless of which handler mapping eventually
serves them.

**Constant-time key comparison**: Timing attacks on string equality are a real threat for
shared-secret authentication. `MessageDigest.isEqual()` is the JDK's documented constant-time
comparator and costs essentially nothing for short keys.

**Random UUID consumer groups**: Each gateway instance (and each restart) gets a fresh consumer
group. This means the gateway always reads from the latest offset and never interferes with other
consumers of the same topics. The trade-off is that events published while the gateway is down
are never delivered to WebSocket clients — which is acceptable for live market data and position
updates where stale data is worse than a gap.

**Caffeine for health check caching**: The alternative (no cache) generates 50 outbound HTTP
requests/sec per gateway instance under K8s default probe frequency, which can overwhelm the
actuator endpoints of smaller services. The 5-second TTL is a practical balance between freshness
and load.

**`onBackpressureLatest()`**: When a WebSocket client's TCP window fills (slow browser, large
burst), the Reactor pipeline would otherwise block. Dropping older messages and delivering only the
latest is semantically correct for market data (the most recent price is what matters) and avoids
unbounded memory growth for lagging clients.

**Separate management port (8091)**: Isolates Kubernetes probe traffic and Prometheus scraping
from public API traffic. This prevents a health-check storm from consuming HTTP thread capacity on
the main port, and allows network policies to restrict scraping to the cluster's monitoring
namespace without opening port 8091 to external traffic.
