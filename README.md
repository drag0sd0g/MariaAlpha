# MariaAlpha

Full-stack algorithmic trading engine — see [Technical Design Document](docs/technical-design-document.md) for architecture and details.

## Prerequisites

- [just](https://github.com/casey/just) — command runner used for all project tasks

  ```
  brew install just   # macOS
  ```

- [Docker](https://www.docker.com/products/docker-desktop/) — required for infrastructure services

## Quick Start

```bash
cp .env.example .env      # configure database credentials
just run                   # start all infrastructure services
just                       # list available recipes
```

## Infrastructure Services

| Service | Port | Notes |
| --- | --- | --- |
| PostgreSQL 16 | 5432 | Credentials via `.env` |
| Kafka (KRaft) | 9092 | Single-node, no ZooKeeper |
| Prometheus | 9090 | Metrics storage, remote-write enabled |
| Grafana | 3001 | Dashboards — anonymous admin access |
| Alloy | 12345 | Telemetry collector UI; OTLP on 4317/4318 |

Loki (logs) and Tempo (traces) run within the Docker network, reachable by Alloy and Grafana.

### API Gateway (port 8080)

Single front door for the React UI and external clients. Implements:
- REST routing to all backend services (`/api/...`).
- API key authentication via `X-API-Key` header (or `?apiKey=` query parameter for browser WebSocket clients).
- Real-time WebSocket fan-out from Kafka to UI clients.

#### Configuration

| Env var | Required | Description |
|---|---|---|
| `MARIAALPHA_API_KEY` | yes | Shared secret. Without it the gateway rejects every request with HTTP 401. |
| `KAFKA_BOOTSTRAP_SERVERS` | yes | Kafka cluster (default `localhost:9092`). |
| `STRATEGY_ENGINE_URL` | optional | Default `http://localhost:8082`. |
| `ORDER_MANAGER_URL` | optional | Default `http://localhost:8086`. |
| `EXECUTION_ENGINE_URL` | optional | Default `http://localhost:8084`. |
| `POST_TRADE_URL` | optional | Default `http://localhost:8088`. |
| `ANALYTICS_SERVICE_URL` | optional | Default `http://localhost:8095`. |
| `MARKET_DATA_GATEWAY_URL` | optional | Default `http://localhost:8079`. |

#### Quickstart

```bash
export MARIAALPHA_API_KEY=local-dev-key
just run
./gradlew :api-gateway:bootRun
```

### React UI (port 5173 in dev)

The web UI is a Vite + React 18 + TypeScript single-page app. The Phase-1 MVP exposes a Dashboard (live positions, P&L, exposure) and an Order Entry page (manual order submission, active orders, fills). Five additional pages (Market Data, RFQ, Strategies, Analytics, Reconciliation) are scaffolded as placeholders for Phase 2.

#### Quickstart

```bash
just run                    # bring up infra + backend services
cp ui/.env.example ui/.env.local
echo "VITE_MARIAALPHA_API_KEY=local-dev-key" >> ui/.env.local
just ui-install             # one-time: npm install
just ui-dev                 # opens http://localhost:5173
```

The dev server proxies `/api/*` and `/ws/*` to `http://localhost:8080` (the API Gateway), so no CORS configuration is needed.

#### Configuration

| Env var (in `ui/.env.local`) | Required | Description |
|---|---|---|
| `VITE_MARIAALPHA_API_KEY` | yes | Must match the key in repo-root `.env`. |
| `VITE_API_BASE_URL` | no | Leave blank for dev (uses Vite proxy). Set to `http://localhost:8080` only when running `vite preview` against a built bundle. |

#### Production-ish build

```bash
just ui-build               # static assets in ui/dist/
cd ui && npm run preview    # serves dist/ on http://localhost:4173
```

A nginx Dockerfile (`ui/Dockerfile`) is planned for issue 1.10.3 once the full-stack docker-compose flow is documented.

#### Manual order API surface

Manual orders submitted via the Order Entry page hit **`POST /api/execution/orders`** (not `/api/orders`). The distinction matters for external clients:

| Method | Path | Backend | Notes |
|---|---|---|---|
| `POST` | `/api/execution/orders` | execution-engine | Submit a manual order; returns `{orderId, status, acceptedAt}` |
| `DELETE` | `/api/execution/orders/{orderId}` | execution-engine | Cancel a manual order; 204 on success, 404 if unknown/terminal |

## Database

Liquibase migrations run automatically on Spring Boot service startup. Verify schema:

```bash
docker compose exec postgres psql -U mariaalpha -c '\dt'
```

## Observability

The Grafana LGTM stack (Loki, Grafana, Tempo, Mimir/Prometheus) starts with `just run`. Grafana is pre-configured with all datasources and available at [http://localhost:3001](http://localhost:3001).

```mermaid
graph LR
    subgraph Application Services
        J[Java Services<br/>Micrometer + OTLP]
        P[Python Services<br/>prometheus_client + OTLP]
    end

    subgraph Observability Stack
        AL[Grafana Alloy<br/>:12345]
        PROM[Prometheus<br/>:9090]
        LOKI[Loki]
        TEMPO[Tempo]
        GF[Grafana<br/>:3001]
    end

    subgraph Infrastructure
        PG[(PostgreSQL<br/>:5432)]
        KF[Kafka<br/>:9092]
    end

    J -->|/metrics scrape| AL
    P -->|/metrics scrape| AL
    J -->|OTLP traces :4317| AL
    P -->|OTLP traces :4318| AL
    AL -->|remote write| PROM
    AL -->|push| LOKI
    AL -->|OTLP forward| TEMPO
    PROM --> GF
    LOKI --> GF
    TEMPO --> GF
```

Alloy is the unified telemetry collector — it scrapes Prometheus-format metrics endpoints and forwards them to Prometheus via remote write, receives OTLP traces (gRPC `:4317`, HTTP `:4318`) and forwards to Tempo, and pushes logs to Loki. Grafana queries all three backends and supports cross-linking between traces, logs, and metrics.

## CI/CD

GitHub Actions workflows run on every push and PR to `main`:

| Workflow | File | What it checks |
| --- | --- | --- |
| **CI** | `ci.yml` | Java: Spotless, Checkstyle, SpotBugs, tests + JaCoCo. Python: ruff, mypy, pytest. UI: ESLint, Prettier, tsc. |
| **CodeQL** | `codeql.yml` | Security analysis for Java, Python, TypeScript (also runs weekly). |
| **Snyk** | `snyk.yml` | Dependency vulnerability scanning (requires `SNYK_TOKEN` secret). |
| **PR Metadata** | `pr-metadata.yml` | Auto-populates labels, milestone, assignee, and project from linked issues. |

Python and UI jobs skip automatically when no source files exist yet. JaCoCo and test reports are uploaded as build artifacts. Snyk requires a `SNYK_TOKEN` repository secret — obtain one from [snyk.io](https://snyk.io).

### Branch rules

Direct pushes to `main` are not allowed — all changes go through pull requests. The `Java (lint + test)` and `CodeQL (java-kotlin)` checks must pass before merging. Branches are auto-deleted after merge.