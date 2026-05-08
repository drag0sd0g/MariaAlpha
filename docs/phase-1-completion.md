# MariaAlpha — Phase 1 Completion Record

**Completed:** May 2026  
**Branch merged:** `main`  
**Issues closed:** [1.1.1](https://github.com/drag0sd0g/MariaAlpha/issues/1) – [1.10.3](https://github.com/drag0sd0g/MariaAlpha/issues/52) (47 issues across 10 sections)

---

## What Phase 1 Is

Phase 1 is the MVP of MariaAlpha: a working, end-to-end algorithmic trading engine that can ingest live market data, run a VWAP execution strategy enhanced by an ML signal, pass orders through a risk check chain, route them to either a simulated exchange or Alpaca paper trading, and track the resulting positions and P&L — all running as a fully observable Docker Compose stack with a React UI, production-grade CI, and a clean-checkout quickstart.

The acceptance bar for Phase 1 was:

> A single automated test must traverse every box in the §2.6.1 Tick-to-Trade sequence diagram with the full 14-service stack running. It must pass in CI on every pull request.

That test exists at `e2e-tests/src/test/java/com/mariaalpha/e2e/SimulatedHappyPathE2ETest.java` and gates every merge to `main`.

---

## Completed Work by Section

### 1.1 Project Scaffolding & Infrastructure (issues 1–9)

Gradle 9 monorepo with 8 modules (`proto`, `market-data-gateway`, `strategy-engine`, `execution-engine`, `order-manager`, `post-trade`, `api-gateway`, `e2e-tests`). Java 21 toolchain, Spotless, Checkstyle, SpotBugs, JaCoCo, and Spotless applied uniformly across all modules. Python toolchain: ruff, mypy, pytest with coverage.

`justfile` with 22 recipes covering build, test, lint, docker, smoke testing, and observability verification. `docker-compose.yml` with 16 containers (8 infra, 7 app services, UI) with health checks on every service. Liquibase migrations run automatically at Spring Boot startup — 8 changelogs across order-manager and post-trade. Kafka in KRaft mode (no ZooKeeper) with 8 topics pre-created with explicit retention policies. Grafana LGTM stack (Prometheus, Loki, Tempo, Alloy, Grafana 11) with datasources auto-provisioned and three CI workflows (CI, CodeQL, Snyk).

### 1.2 Market Data Gateway (issues 10–16)

Spring Boot / WebFlux service on ports 8079 (app) and 8081 (management). Two `@Profile`-gated adapters: `SimulatedMarketDataAdapter` replays a CSV at configurable speed-multiplier (default 10×); `AlpacaMarketDataAdapter` connects to the Alpaca IEX WebSocket, subscribes to symbols from `config/symbols.yml`, and handles both TRADE and QUOTE message types. `TickKafkaPublisher` publishes `MarketTick` events to `market-data.ticks`. In-memory order book per symbol updated on every tick. Auto-reconnect with exponential backoff (1s → 2s → 4s → 8s → 16s, max 5 attempts). Custom `TickReadinessIndicator` and `WebSocketHealthIndicator` for readiness probes.

### 1.3 Strategy Engine (issues 17–22)

Spring Boot service on ports 8082 (app) and 8083 (management). `SymbolStrategyRouter` holds a `ConcurrentHashMap<symbol, strategy>` enabling zero-restart strategy hot-swap via `PUT /api/strategies/{symbol}`. `StrategyRegistry` auto-discovers Spring `@Component` strategies. VWAP strategy: accepts `targetQuantity`, `startTime`, `endTime`, `volumeProfile`; slices the target into time bins; emits LIMIT `OrderSignal`s at bin boundaries. Before acting, calls `MlSignalClient.getSignal()` over gRPC with a 500 ms deadline and a Resilience4j circuit breaker (threshold 5, open 30 s) — on open, proceeds without the ML signal rather than stalling. Published signals go to `strategy.signals`.

### 1.4 ML Signal Service (issues 23–28)

Python 3.12 service with a FastAPI HTTP sidecar (port 8090) and a gRPC server (port 50051). `FeatureEngine` maintains a rolling per-symbol window and computes 15 technical indicators: EMA(20), EMA(50), RSI(14), MACD, ATR(14), volume ratio, realized volatility, multi-period returns, and others. A pre-trained LightGBM classifier (`ml-models/signal_model.joblib`) is loaded at startup; `GetSignal` returns `(direction, confidence)`. `StreamSignals` RPC pushes live updates to connected clients. Hot-reload via `POST /v1/models/reload`. Prometheus metrics exposed at `/metrics`: inference latency histogram, feature staleness gauge, gRPC request counter.

### 1.5 Execution Engine (issues 29–36)

Spring Boot service on ports 8084 (app) and 8085 (management). Two `@Profile`-gated exchange adapters: `SimulatedExchangeAdapter` matches LIMIT orders against current bid/ask, schedules fills after a configurable `fill-latency-ms` (default 50 ms) with `slippage-bps` (default 2); `AlpacaExchangeAdapter` submits orders via the Alpaca REST API and consumes fills from the `trade_updates` WebSocket. `RiskCheckChain` runs five composable checks in order — `MaxOrderNotional`, `MaxPositionPerSymbol`, `MaxPortfolioExposure`, `MaxOpenOrders`, `DailyLossLimit` — short-circuiting on the first failure. `DirectRouter` implements the `SmartOrderRouter` interface (Phase 2 will replace it with a real SOR). Daily loss limit breach triggers a trading halt; resumable via `POST /api/execution/resume`. Order lifecycle events published to `orders.lifecycle`, routing decisions to `routing.decisions`, risk alerts to `analytics.risk-alerts`. REST: `POST /api/execution/orders`, `DELETE /api/execution/orders/{id}`, `GET /api/execution/status`.

### 1.6 Order Manager (issues 37–41)

Spring Boot service on ports 8086 (app) and 8087 (management), sharing the Postgres instance with post-trade. Four Liquibase changelogs: `orders`, `fills`, `positions`, `portfolio_snapshots`. `OrderLifecycleConsumer` processes events from `orders.lifecycle`; `PositionService.applyFill` handles open/scale-in/close/flip position transitions. Unrealized P&L is mark-to-market against the latest tick from a `MarkPriceCache` (falls back to fill price when ticks are absent). Portfolio aggregates (total P&L, gross/net exposure, open position count) are computed in real time. Position snapshots published to `positions.updates` after every fill. REST: `GET /api/positions`, `GET /api/positions/{symbol}`, `GET /api/portfolio/summary`, `GET /api/orders`, `GET /api/orders/{id}`.

### 1.7 Post-Trade / TCA (issue 42)

Spring Boot service on ports 8088 (app) and 8089 (management). `ArrivalSnapshotService` captures the bid/ask mid at order signal time (i.e., before execution starts) by listening to `market-data.ticks`. `TcaCalculator` computes four metrics per completed order: slippage vs. arrival mid, implementation shortfall, VWAP benchmark deviation, and spread cost. Results persisted to `tca_results` and published to `analytics.tca`. REST: `GET /api/tca/{orderId}`. Two additional Liquibase changelogs (`arrival_snapshots`, `tca_results`).

### 1.8 API Gateway (issues 43–45)

Spring Cloud Gateway on ports 8080 (app) and 8091 (management). Routes all `/api/*` paths to the correct backend service via `application.yml` route configuration. `ApiKeyAuthenticationFilter` (WebFilter) enforces `X-API-Key` on every request including WebSocket handshakes — HTTP 401 on missing or invalid key. WebSocket fan-out on four endpoints: `/ws/positions` (topic `positions.updates`), `/ws/orders` (topic `orders.lifecycle`), `/ws/market-data` (topic `market-data.ticks`), `/ws/alerts` (topic `analytics.risk-alerts`). Aggregate health check combines all downstream service health indicators for the readiness probe.

### 1.9 React UI (issues 46–49)

Vite 5 + React 18 + TypeScript 5.6 (strict mode) SPA. **Dashboard**: portfolio summary cards (total value, cash, net exposure, total P&L), 10-minute daily P&L line chart (ring buffer at 1 Hz using `useRef` to avoid re-render overhead), positions table sorted by absolute quantity with colour-coded P&L. **Order Entry**: order form (MARKET/LIMIT/STOP with conditional price fields and client-side validation), active orders table with cancel, fill history table fetched via `Promise.allSettled` over order detail endpoints. **WebSocket hook**: `useWebSocket` manages the full connection lifecycle — exponential backoff reconnect (1s → 30s cap), `onMessage` in `useRef` to prevent accidental reconnects, stable JSON key for the dependency array. Three Zustand 5 stores (`positionStore`, `orderStore`, `connectionStore`) act as the UI's in-process event-sourced cache. `ConnectionStatus` overlay at z-50 shows a debounced (5 s) red banner on disconnect. nginx production container (`ui/Dockerfile`, multi-stage build) with `/api/` and `/ws/` reverse-proxy to api-gateway; served on port 5173. 12 Vitest unit tests (Dashboard, OrderEntry, useWebSocket hook).

### 1.10 End-to-End Integration (issues 50–52)

**1.10.1** — `e2e-tests/` Gradle module with `SimulatedHappyPathE2ETest`: boots the full 14-service stack via `ComposeContainer`, binds VWAP to AAPL via REST, polls for fill, asserts netQuantity = 100, avgEntryPrice within CSV bid/ask + slippage, unrealizedPnl non-null, portfolio openPositions = 1, order status FILLED strategy VWAP, tradingHalted false. Runs in CI in the `e2e` job (depends on `java` job, 25-minute timeout). `strategy.signals` topic added explicitly to `config/kafka/create-topics.sh`.

**1.10.2** — `docker-compose.yml` `execution-engine` service parameterised with `${EXECUTION_PROFILE:-simulated}` and `ALPACA_*` env passthrough. `docs/runbooks/alpaca-smoke-test.md` documents the 9-step manual verification (credentials → stack start → tick confirmation → resting order → marketable order → fill/position/P&L verification → cancel → Alpaca dashboard cross-check → teardown). `just smoke-alpaca` recipe with credential guard.

**1.10.3** — `ui/Dockerfile` (multi-stage Node 20 builder → nginx 1.27 alpine), `ui/nginx.conf` (SPA fallback + `/api/` + `/ws/` reverse-proxy), `ui/.dockerignore`. `docker-compose.yml` `ui` service uncommented and finalised. `.env.example` updated with all required variables. `justfile` `docker-build` and `verify` recipes. README `## Quickstart` section (7 steps + troubleshooting table).

---

## Additional Fixes Completed During Phase 1 Audit

Four bugs found during the Phase 1 completeness audit (May 2026) and fixed on the same branch:

| # | Bug | Fix |
|---|-----|-----|
| A | `RoutingDecisionPublisher` was publishing routing decisions to `orders.lifecycle` instead of `routing.decisions` — polluting the lifecycle topic with the wrong event type and leaving `routing.decisions` empty | One-line fix: `config.ordersLifecycleTopic()` → `config.routingDecisionsTopic()` |
| B | Alloy config only scraped itself and Prometheus — no application service metrics were being collected | Added `prometheus.scrape "java_services"` (6 services on their management ports) and `prometheus.scrape "ml_signal_service"` to `config/alloy/config.alloy` |
| C | `market-data-gateway` was missing `io.micrometer:micrometer-registry-prometheus` — the `/actuator/prometheus` endpoint returned 404 despite being listed in the exposure config | Added `runtimeOnly("io.micrometer:micrometer-registry-prometheus")` to `market-data-gateway/build.gradle.kts` |
| D | No Grafana dashboards were provisioned — only datasources existed | Created `config/grafana/provisioning/dashboards/dashboards.yaml` (provider config) and `trading-pipeline.json` (9-panel "MariaAlpha — Trading Pipeline" dashboard covering tick ingestion, signals, orders, fills, risk rejections, open positions, ML P99 latency, TCA rate, and service health) |
| E | UI unit tests (Vitest) were not gated in CI — the `ui` job ran lint and typecheck but never called `npm test` | Added `npm test` step to the `ui` job in `.github/workflows/ci.yml` |

---

## Test Coverage Summary

| Layer | Mechanism | Count |
|---|---|---|
| Unit | JUnit 5 / Vitest | ~80 Java unit tests; 12 UI tests |
| Integration | Testcontainers (Kafka / PostgreSQL) | 13 `@Tag("integration")` tests, one per service |
| End-to-end | Testcontainers ComposeContainer | 1 test (`SimulatedHappyPathE2ETest`) covering the full 14-service pipeline |
| Manual smoke | Runbook (`docs/runbooks/alpaca-smoke-test.md`) | Alpaca paper trading verified once per release branch |

---

## Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Build | Gradle | 9.4 |
| Language (services) | Java | 21 |
| Language (ML) | Python | 3.12 |
| Language (UI) | TypeScript | 5.6 |
| Framework (Java) | Spring Boot | 3.5 |
| Framework (UI) | React + Vite | 18.3 + 5.4 |
| Messaging | Apache Kafka (KRaft) | 3.9 |
| Database | PostgreSQL | 16 |
| Migrations | Liquibase | (Spring Boot managed) |
| ML | LightGBM | 4.x |
| gRPC | protoc + grpc-java + grpcio | 3.25 / 1.70 |
| API gateway | Spring Cloud Gateway | 4.x |
| State (UI) | Zustand | 5.0 |
| Observability | Grafana LGTM (Alloy + Prometheus + Loki + Tempo) | Alloy 1.6, Grafana 11.5 |
| Container serving (UI) | nginx | 1.27 |
| CI | GitHub Actions | — |
| Security scans | CodeQL + Snyk | — |

---

## What Phase 1 Does NOT Do

These are explicit non-goals carried forward to Phase 2 or later:

- **Smart Order Router**: `DirectRouter` is a pass-through stub. Real venue scoring, dark pools, and internalization are Phase 2 (issues 2.1.1–2.1.2).
- **TWAP / Momentum / Implementation Shortfall strategies**: only VWAP is implemented. Phase 2 adds 4 more algorithms (2.1.5–2.1.9).
- **Advanced order types** (IOC, FOK, GTC, Iceberg, Pegged): only MARKET, LIMIT, STOP are implemented. Phase 2 (2.1.3–2.1.4).
- **Sector/Beta/ADV risk checks**: Phase 2 (2.2.1–2.2.3). Only the 5 MVP risk checks are active.
- **End-of-day reconciliation engine**: Phase 2 (2.6.1).
- **Redis distributed cache**: Phase 2 (2.7.4). Position lookups are in-process.
- **Helm / Kubernetes deployment**: Phase 2 (2.7.1). Only Docker Compose is supported.
- **Regime classifier** (Random Forest model): Phase 2 (2.3.1). ML service returns direction + confidence from the signal model only.
- **Playwright UI e2e tests**: Phase 2. Only REST-layer assertions are in the e2e test.
- **RFQ, Strategies, Analytics, Reconciliation UI screens**: Phase 2 (2.5.1–2.5.4). Currently `ComingSoon` stubs.
- **FIX protocol gateway**: Phase 3 (3.4.3).
- **IBKR / Tokyo Stock Exchange support**: Phase 3 (3.1.x–3.3.x).

---

## Phase 2 Entry Point

The roadmap for Phase 2 is documented in [§11 of the TDD](technical-design-document.md#phase-2-full-desk-workflows--sor--rich-analytics). The natural first issues to pick up are:

1. **2.6.2** — Grafana Trading Pipeline dashboard (already partially delivered by audit fix D above; Phase 2 completes the Portfolio & Risk and Post-Trade dashboards)
2. **2.7.1** — Helm charts for Kubernetes deployment
3. **2.1.1** — Smart Order Router with venue scoring
4. **2.1.5** — TWAP strategy
5. **2.7.4** — Redis distributed position cache
