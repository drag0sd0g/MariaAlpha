# Analytics Service — Implementation Notes

A Python 3.12 / FastAPI service that delivers the Phase-2 analytics layer:

| Issue | Surface |
| --- | --- |
| 2.2.4 | Flow-toxicity / adverse-selection detector |
| 2.2.5 | PnL attribution (spread / market / commission / timing / residual) |
| 2.2.6 | Axe matching (client interest book + cross suggestions) |
| 4.6.1 | Portfolio construction (optimisers, Black-Litterman, factors, rebalancer) |
| 4.6.1 | Firm-wide risk engine (VaR / ES / component VaR / stress) |

Lives at `analytics-service/` alongside `ml-signal-service/`; same packaging
pattern (`pyproject.toml`, `requirements.txt`, `Dockerfile`, FastAPI + Uvicorn
on port 8095, Prometheus `/metrics` scraped by Alloy).

## Module map

```
analytics-service/
├── src/analytics/
│   ├── __main__.py           # process entry: build components, start consumers, run uvicorn
│   ├── config.py             # pydantic-settings Settings, env-prefix ANALYTICS_
│   ├── metrics.py            # Prometheus metrics (toxicity / pnl / axes / portfolio / risk)
│   ├── numeric.py            # FloatArray type alias shared by the numpy modules
│   ├── api/
│   │   ├── app.py                # FastAPI surface + /health, /actuator/health, /metrics
│   │   └── portfolio_routes.py   # 4.6.1 — /v1/analytics/portfolio/** and /risk/**
│   ├── toxicity/detector.py  # 2.2.4 — markout per fill, rolling mean, alerts
│   ├── pnl/attribution.py    # 2.2.5 — Kissell-Glantz decomposition
│   ├── axes/matcher.py       # 2.2.6 — axe book + match ranker
│   ├── portfolio/            # 4.6.1 — construction
│   │   ├── reference.py          # config/portfolio.yml loader + block correlation prior
│   │   ├── state.py              # book rebuilt from positions.updates; NAV, weights
│   │   ├── covariance.py         # sample / EWMA / Ledoit-Wolf / PSD repair / annualise
│   │   ├── service.py            # CovarianceService — ties the cache, prior and estimator
│   │   ├── optimizers.py         # mean-variance, min-var, max-Sharpe, ERC, frontier
│   │   ├── black_litterman.py    # equilibrium + posterior (Woodbury and naive forms)
│   │   ├── factors.py            # fundamental (BARRA-lite) + PCA decomposition
│   │   ├── rebalance.py          # cost-aware rebalancer + basket payload
│   │   └── basket_client.py      # optional POST to execution-engine (off by default)
│   ├── risk/                 # 4.6.1 — the risk engine
│   │   ├── engine.py             # parametric / historical / MC VaR, ES, component VaR
│   │   ├── stress.py             # scenario loader + revaluation
│   │   └── report.py             # self-contained HTML risk report
│   ├── consumer/
│   │   ├── market_data.py    # consumes market-data.ticks → MarketDataCache (+ bars/returns)
│   │   ├── tca_consumer.py   # consumes analytics.tca → attribution + toxicity.on_fill
│   │   ├── orders_consumer.py# consumes orders.lifecycle → axe match suggestions
│   │   └── positions.py      # consumes positions.updates → PortfolioState
│   └── publisher/
│       ├── risk_alert.py     # publishes analytics.risk-alerts (FLOW_TOXICITY, VAR/STRESS breach)
│       └── risk_model.py     # publishes analytics.risk-model (covariance model)
└── tests/                    # ~430 unit + integration tests (pytest, FastAPI TestClient)
```

## Component sketches

### Flow toxicity (2.2.4)

`FlowToxicityDetector` keeps a per-(strategy, symbol) deque of recent fills and
computes a *signed markout in basis points* at each configured horizon
(default `60s / 300s / 1800s`):

```
markout = sign × (price_after_horizon − fill_price) / fill_price × 10_000
       sign = +1 if SELL, −1 if BUY    # positive = adverse selection
```

The detector advances by **either** an `on_fill` push **or** a `tick()` poll
(the `__main__` loop ticks every 5 s). When the rolling mean per
(strategy, horizon) crosses `ANALYTICS_TOXICITY_THRESHOLD_BPS` and at least
`ANALYTICS_TOXICITY_MIN_OBSERVATIONS` samples are present, a `FLOW_TOXICITY`
event is published to `analytics.risk-alerts`. A 60-second cooldown per
(strategy, horizon) prevents alert storms.

Snapshots are read at `GET /v1/analytics/flow/toxicity[?strategy=]`.

### PnL attribution (2.2.5)

`PnlAttributionEngine` decomposes the realised PnL of every parent order on
the `analytics.tca` stream into five USD components:

| Component | Formula |
| --- | --- |
| `spread_usd` | `−(spread_cost_bps / 10_000) × arrival_price × qty` (always a cost) |
| `timing_usd` | `(arrival_price − decision_mid_price) × signed_qty` when the TCA payload carries `decision_mid_price`; `0.0` otherwise |
| `market_usd` | `(vwap_benchmark − arrival_price) × signed_qty` |
| `commission_usd` | `−|commission_total|` or `−(commission_bps / 10_000) × realized_avg × qty` |
| `residual_usd` | `realized_pnl − (spread + timing + market + commission)` — surfaces model error |
| `realized_pnl_usd` | `(vwap_benchmark − realized_avg) × signed_qty` |

State is in-memory; `analytics.tca` replay rebuilds it after restart.

Surfaced at `GET /v1/analytics/pnl/attribution[?strategy=]`, `/{orderId}`,
`/by-strategy/{strategy}` (min/median/max/sum per component).

### Axe matcher (2.2.6)

`AxeMatcher` is an in-memory axe book keyed by `axe_id` with a parallel refresh
index keyed by `(client_id, symbol, side)`. Each axe has:

- a TTL (`ANALYTICS_AXE_DEFAULT_TTL_MINUTES`, default 60 min);
- a `confidence` ∈ [0, 1] that decays linearly to 0 over the TTL — a fresh
  refresh pushes it back to 1.0;
- a `refresh_count` for tiebreak in the ranker.

`OrdersConsumer` reads `orders.lifecycle`, filters `status == "SUBMITTED"`,
and calls `matcher.match(IncomingLeg)`. Candidates are opposite-side axes on
the same symbol with `remaining > 0`, ranked by
`(−confidence, −remaining, −refresh_count, published_at)`. Matched quantity is
debited immediately so concurrent callers cannot double-fill. Fully-consumed
axes are auto-removed.

Surfaced at:
- `POST /v1/analytics/axes` — publish or refresh (returns 201)
- `DELETE /v1/analytics/axes/{axeId}` — cancel (204 / 404)
- `GET /v1/analytics/axes[?symbol=&side=]` — snapshot + stats
- `GET /v1/analytics/axes/matches/{orderId}` — last match suggestions for an order

## Prometheus metrics

| Metric | Type | Labels |
| --- | --- | --- |
| `mariaalpha_analytics_toxicity_markout_bps` | Gauge | `strategy`, `symbol`, `horizon` |
| `mariaalpha_analytics_toxicity_alerts_total` | Counter | `strategy`, `horizon` |
| `mariaalpha_analytics_pnl_attribution_usd` | Gauge | `strategy`, `component` |
| `mariaalpha_analytics_axes_active` | Gauge | `symbol`, `side` |
| `mariaalpha_analytics_axes_matches_total` | Counter | `symbol`, `match_quality` (HIGH/MEDIUM/LOW) |

All five are exposed on `analytics-service:8095/metrics`. The Alloy config
(`config/alloy/config.alloy`) scrapes this endpoint every 15 s.

## Configuration

All knobs are environment-variable driven with the `ANALYTICS_` prefix:

| Env var | Default | Purpose |
| --- | --- | --- |
| `ANALYTICS_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers (`kafka:9094` in compose) |
| `ANALYTICS_TOXICITY_HORIZONS_SECONDS` | `(60, 300, 1800)` | Markout evaluation horizons |
| `ANALYTICS_TOXICITY_THRESHOLD_BPS` | `5.0` | Mean-markout threshold for alerting |
| `ANALYTICS_TOXICITY_MIN_OBSERVATIONS` | `10` | Min samples before an alert can fire |
| `ANALYTICS_COMMISSION_BPS` | `0.5` | Fallback commission when TCA row lacks `commission_total` |
| `ANALYTICS_AXE_DEFAULT_TTL_MINUTES` | `60` | Axe TTL |
| `ANALYTICS_AXE_MATCH_MIN_QUANTITY` | `100` | Min-fillable quantity gating |

## Wiring

- **API Gateway** — `/api/analytics/**` (client-facing) is rewritten to
  `/v1/analytics/**` by `RouteConfiguration#analyticsRoute` and proxied to
  `ANALYTICS_SERVICE_URL` (`http://analytics-service:8095` in compose), so the
  FastAPI app serves `/v1/analytics/*` routes. Listed as a *required* downstream
  so the gateway's aggregate health probe pings `/actuator/health` (FastAPI
  returns `{"status": "UP"}` for Spring-Boot compatibility). Requires the
  matching `ANALYTICS_SERVICE_MANAGEMENT_URL` env var so the gateway doesn't
  fall back to its own loopback.
- **Kafka topics consumed**: `analytics.tca`, `market-data.ticks`,
  `orders.lifecycle`, `positions.updates` (4.6.1, from `earliest` so the book
  survives a restart).
- **Kafka topics produced**: `analytics.risk-alerts` (FLOW_TOXICITY,
  PORTFOLIO_VAR_BREACH, STRESS_LIMIT_BREACH) and `analytics.risk-model` (4.6.1 —
  compacted, consumed by execution-engine's `IntradayVarCheck`).
- **Config mounts** (4.6.1): `config/portfolio.yml` and
  `config/stress-scenarios.yml`, read-only at `/app/config/`.
- **docker-compose** — service block at `analytics-service:8095`,
  `depends_on: kafka: service_healthy`, healthcheck pings `/health`.
- **Helm** — not yet wired into the umbrella chart; tracked as a follow-up.

## Testing

- `cd analytics-service && .venv/bin/pytest tests/` — ~430 unit and FastAPI
  TestClient integration tests cover all six engines plus the REST surface (87%
  line coverage; the 4.6.1 modules are 89-100%).
- `cd analytics-service && .venv/bin/mypy src/` — strict, zero errors. numpy
  arrays are annotated `FloatArray` (see `numeric.py`) because numpy's stubs
  widen `float64` arithmetic to `floating[Any]`.
- `cd e2e-tests && ../gradlew test --tests AnalyticsServiceE2ETest` —
  Testcontainers compose-stack test that exercises `/v1/analytics/axes` and
  the toxicity/PnL empty-state JSON through the API Gateway.
- `cd e2e-tests && ../gradlew test --tests PortfolioRiskE2ETest` — 4.6.1 through
  the gateway, ending with the rebalancer's basket payload being accepted by
  `POST /api/execution/baskets`.

## Deep dives

- [Portfolio construction](../strategies/portfolio-construction.md) — covariance
  estimation, the five optimisers, Black-Litterman, factor decomposition, the
  cost-aware rebalancer.
- [Portfolio risk engine](../strategies/portfolio-risk-engine.md) — VaR/ES,
  component VaR, stress scenarios, and the `analytics.risk-model` contract with
  execution-engine.
