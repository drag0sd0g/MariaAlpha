# MariaAlpha — Documentation Index

This folder is organised by purpose, not chronology. Start at the [Technical Design Document](technical-design-document.md) for the system as a whole; drill into a service explainer when you need to change that service; consult the strategy docs when you need to reason about a specific algorithm.

```
docs/
├── README.md                           ← you are here
├── technical-design-document.md        ← top-level architecture, FRs/NFRs, roadmap
├── cloud-deployment-plan.md            ← long-form plan for the cloud migration
│
├── services/                           ← one deep-dive per microservice
├── strategies/                         ← one deep-dive per execution / pricing strategy
├── guides/                             ← cross-cutting topical guides
└── runbooks/                           ← operational procedures (install, smoke, rotate)
```

---

## Reference docs (top-level)

| Doc | When to read |
|---|---|
| [Technical Design Document](technical-design-document.md) | Whole-system view: functional + non-functional requirements, architecture, data model, Kafka topics, scalability, resilience, observability, deployment, roadmap. |
| [Finance mental map](finance-mental-map.md) | Engineer-friendly reference of every financial concept the project touches — market structure, order taxonomy, execution algos, ML signals, RFQ, risk checks, options Greeks, post-trade, quant primitives. Refresh here before reading any finance-flavoured code. |
| [Cloud deployment plan](cloud-deployment-plan.md) | Long-form plan for moving the OrbStack-Kubernetes deployment to Oracle Cloud. |

---

## Services

Each microservice has a dedicated explainer covering its architecture, data flow, configuration, metrics, REST surface, and test strategy. Sequenced roughly by the trading pipeline:

| Service | Explainer | Role |
|---|---|---|
| Market Data Gateway | *(deep-dive doc TBD — see TDD §5.2.1)* | Subscribes to Alpaca; normalises ticks to `market-data.ticks`; serves the live order book over gRPC. |
| **Strategy Engine** | [`services/strategy-engine.md`](services/strategy-engine.md) | Hosts the strategy registry, the ML confirm/veto gate, the RFQ pricing engine, the [options pricer](strategies/options-pricing.md), the [algo execution API](strategies/algo-execution-api.md), and [multi-market trading hours](strategies/multi-market-trading-hours.md). |
| ML Signal Service | [`services/ml-signal-service.md`](services/ml-signal-service.md) | Python gRPC + FastAPI. LightGBM signal model + Random Forest regime classifier. |
| Execution Engine | [`services/execution-engine.md`](services/execution-engine.md) | Order lifecycle, ten-check risk chain (incl. [intraday VaR](strategies/intraday-var.md) and [correlated positions](strategies/correlated-positions.md)), [pegged orders](strategies/pegged-orders.md), exchange adapters, daily-loss kill-switch. |
| Smart Order Router | [`services/smart-order-router.md`](services/smart-order-router.md) | Scored multi-criteria venue selection (sub-component of Execution Engine). |
| Internal Crossing Engine | [`services/internal-crossing-engine.md`](services/internal-crossing-engine.md) | In-process midpoint matching for desk-vs-desk flow (sub-component of Execution Engine). |
| Order Manager | [`services/order-manager.md`](services/order-manager.md) | System of record for orders, fills, positions, portfolio P&L, [currency exposure](strategies/currency-exposure.md). |
| Post-Trade | *(deep-dive doc TBD — see TDD §5.2.6)* | TCA computation, end-of-day reconciliation, [trade allocation](strategies/trade-allocation.md). |
| Analytics Service | [`services/analytics-service.md`](services/analytics-service.md) | Python FastAPI. Flow toxicity, PnL attribution, axe matcher. |
| API Gateway | [`services/api-gateway.md`](services/api-gateway.md) | Unified REST + WebSocket entry point. API-key auth. |
| React UI | [`services/ui.md`](services/ui.md) | TypeScript + Vite + Tailwind + Recharts. Dashboard, Order Entry, RFQ, etc. |
| ML Signal Service animation | [`services/ml-signal-service-animation.html`](services/ml-signal-service-animation.html) | Visual walkthrough of the signal-generation pipeline. |

---

## Strategies & trading features

Each *execution* strategy implements `TradingStrategy` and registers with the Strategy Engine's `StrategyRegistry`. The same folder also documents the pricing engines, risk checks, and trading features that ship alongside the strategies — they share the engine, the risk chain, or the order pipeline even though they aren't `TradingStrategy` implementations.

### Execution strategies

| Strategy | Explainer | Style |
|---|---|---|
| VWAP | [`strategies/vwap.md`](strategies/vwap.md) | Schedule: slice along a historical volume profile. |
| TWAP | [`strategies/twap.md`](strategies/twap.md) | Schedule: equal slices across equal time intervals. |
| Momentum | [`strategies/momentum.md`](strategies/momentum.md) | Alpha: EMA crossover + RSI + volume confirmation. |
| Implementation Shortfall | [`strategies/implementation-shortfall.md`](strategies/implementation-shortfall.md) | Schedule: front-loaded along an Almgren–Chriss trajectory. |
| POV | [`strategies/pov.md`](strategies/pov.md) | Reactive: participate as a fraction of traded volume. |
| Close | [`strategies/close.md`](strategies/close.md) | Schedule: working-into-the-close + MOC clip. |

### Pricing engines

| Feature | Explainer | What it does |
|---|---|---|
| RFQ pricing | [`strategies/rfq-pricing.md`](strategies/rfq-pricing.md) | Inventory-skewed, vol- and ADV-relative two-way quote. |
| Options pricing | [`strategies/options-pricing.md`](strategies/options-pricing.md) | Black-Scholes-Merton pricing, Greeks, and implied-vol solver (Strategy Engine REST). |

### Risk checks

| Feature | Explainer | What it does |
|---|---|---|
| Intraday VaR | [`strategies/intraday-var.md`](strategies/intraday-var.md) | Pre-trade parametric VaR limit over the simulated post-trade portfolio. |
| Correlated positions | [`strategies/correlated-positions.md`](strategies/correlated-positions.md) | Caps aggregate exposure across correlation clusters of symbols. |
| Currency exposure | [`strategies/currency-exposure.md`](strategies/currency-exposure.md) | Read-side per-currency exposure and P&L aggregation in the Order Manager. |

### Order handling & access

| Feature | Explainer | What it does |
|---|---|---|
| Pegged orders | [`strategies/pegged-orders.md`](strategies/pegged-orders.md) | PEGGED order type: tracks midpoint / bid / ask, re-pricing as the NBBO moves. |
| Trade allocation | [`strategies/trade-allocation.md`](strategies/trade-allocation.md) | Post-trade fill allocation across sub-accounts (pro-rata, FIFO). |
| Algo execution API | [`strategies/algo-execution-api.md`](strategies/algo-execution-api.md) | Programmatic REST + WebSocket surface for external algo clients. |
| Multi-market trading hours | [`strategies/multi-market-trading-hours.md`](strategies/multi-market-trading-hours.md) | Session calendars gating strategy evaluation per listing market. |

---

## Guides

Cross-cutting topical pieces. Read these when the context isn't service-specific.

| Guide | Topic |
|---|---|
| [`guides/project-reactor.md`](guides/project-reactor.md) | How `Flux` / `Mono` are used in the Market Data Gateway and the API Gateway WebSocket layer. |
| [`guides/tca-methodology.md`](guides/tca-methodology.md) | TCA metrics produced by Post-Trade: slippage, implementation shortfall, VWAP benchmark, spread cost. |

---

## Runbooks

Operational procedures, ordered by how often you'd actually run them:

| Runbook | When |
|---|---|
| [`runbooks/helm-install.md`](runbooks/helm-install.md) | Installing or upgrading the umbrella chart on OrbStack-Kubernetes / Docker Desktop / minikube / kind. |
| [`runbooks/alpaca-smoke-test.md`](runbooks/alpaca-smoke-test.md) | Manual 9-step verification against Alpaca paper trading after a release branch. |
| [`runbooks/helm-rotate-secrets.md`](runbooks/helm-rotate-secrets.md) | Re-sealing a `SealedSecret` and redeploying. |

---

## Conventions

- **Per-component explainers** live under `services/` and are named after the service (`strategy-engine.md`, not `strategy-engine-explainer.md`). The `-explainer` suffix was redundant once the folder named its purpose.
- **Per-strategy explainers** live under `strategies/`.
- **Cross-cutting guides** live under `guides/`.
- **Runbooks** are imperative ("do this, then this") and live under `runbooks/`.
- **Reference docs** (the TDD, long-form plans) stay at the docs/ root because they are *about* the whole system rather than any one part.

When adding a new service or strategy, mirror an existing explainer's structure: Overview → Architecture diagram → Components/Data flow → Configuration → REST/Kafka surface → Metrics → Testing → Deploy → References. The `services/order-manager.md` and `strategies/vwap.md` are good templates.
