# MariaAlpha — Documentation Index

This folder is organised by purpose, not chronology. Start at the [Technical Design Document](technical-design-document.md) for the system as a whole; drill into a service explainer when you need to change that service; consult the strategy docs when you need to reason about a specific algorithm.

```
docs/
├── README.md                           ← you are here
├── technical-design-document.md        ← top-level architecture, FRs/NFRs, roadmap
├── phase-1-completion.md               ← record of the MVP scope
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
| [Phase 1 completion record](phase-1-completion.md) | What "MVP" means in this project — what shipped, how it was tested, where the gaps are. |
| [Cloud deployment plan](cloud-deployment-plan.md) | Long-form plan for moving the OrbStack-Kubernetes deployment to Oracle Cloud (Phase 2.8). |

---

## Services

Each microservice has a dedicated explainer covering its architecture, data flow, configuration, metrics, REST surface, and test strategy. Sequenced roughly by the trading pipeline:

| Service | Explainer | Role |
|---|---|---|
| Market Data Gateway | *(deep-dive doc TBD — see TDD §5.2.1)* | Subscribes to Alpaca; normalises ticks to `market-data.ticks`; serves the live order book over gRPC. |
| **Strategy Engine** | [`services/strategy-engine.md`](services/strategy-engine.md) | Hosts the strategy registry, the ML confirm/veto gate, and the RFQ pricing engine. |
| ML Signal Service | [`services/ml-signal-service.md`](services/ml-signal-service.md) | Python gRPC + FastAPI. LightGBM signal model + Random Forest regime classifier. |
| Execution Engine | [`services/execution-engine.md`](services/execution-engine.md) | Order lifecycle, risk-check chain, exchange adapters, daily-loss kill-switch. |
| Smart Order Router | [`services/smart-order-router.md`](services/smart-order-router.md) | Scored multi-criteria venue selection (sub-component of Execution Engine). |
| Internal Crossing Engine | [`services/internal-crossing-engine.md`](services/internal-crossing-engine.md) | In-process midpoint matching for desk-vs-desk flow (sub-component of Execution Engine). |
| Order Manager | [`services/order-manager.md`](services/order-manager.md) | System of record for orders, fills, positions, portfolio P&L. |
| Post-Trade | *(deep-dive doc TBD — see TDD §5.2.6)* | TCA computation, end-of-day reconciliation. |
| Analytics Service | [`services/analytics-service.md`](services/analytics-service.md) | Python FastAPI. Flow toxicity, PnL attribution, axe matcher. |
| API Gateway | [`services/api-gateway.md`](services/api-gateway.md) | Unified REST + WebSocket entry point. API-key auth. |
| React UI | [`services/ui.md`](services/ui.md) | TypeScript + Vite + Tailwind + Recharts. Dashboard, Order Entry, RFQ, etc. |
| ML Signal Service animation | [`services/ml-signal-service-animation.html`](services/ml-signal-service-animation.html) | Visual walkthrough of the signal-generation pipeline. |

---

## Strategies

Each *execution* strategy implements `TradingStrategy` and registers with the Strategy Engine's `StrategyRegistry`. The RFQ engine is on this page too — it's a *pricing* algorithm rather than a `TradingStrategy`, but it shares the same engine and emits the same `OrderSignal`.

| Strategy | Explainer | Issue | Style |
|---|---|---|---|
| VWAP | [`strategies/vwap.md`](strategies/vwap.md) | 1.3.2 | Schedule: slice along a historical volume profile. |
| TWAP | [`strategies/twap.md`](strategies/twap.md) | 2.1.5 | Schedule: equal slices across equal time intervals. |
| Momentum | [`strategies/momentum.md`](strategies/momentum.md) | 2.1.6 | Alpha: EMA crossover + RSI + volume confirmation. |
| Implementation Shortfall | [`strategies/implementation-shortfall.md`](strategies/implementation-shortfall.md) | 2.1.7 | Schedule: front-loaded along an Almgren–Chriss trajectory. |
| POV | [`strategies/pov.md`](strategies/pov.md) | 2.1.8 | Reactive: participate as a fraction of traded volume. |
| Close | [`strategies/close.md`](strategies/close.md) | 2.1.9 | Schedule: working-into-the-close + MOC clip. |
| **RFQ pricing** | [`strategies/rfq-pricing.md`](strategies/rfq-pricing.md) | 2.4.1 + 2.4.2 | Pricing: inventory-skewed, vol- and ADV-relative two-way quote. |

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
- **Reference docs** (the TDD, phase summaries, long-form plans) stay at the docs/ root because they are *about* the whole system rather than any one part.

When adding a new service or strategy, mirror an existing explainer's structure: Overview → Architecture diagram → Components/Data flow → Configuration → REST/Kafka surface → Metrics → Testing → Deploy → References. The `services/order-manager.md` and `strategies/vwap.md` are good templates.
