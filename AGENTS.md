# MariaAlpha — Agent Context

MariaAlpha is a full-stack algorithmic trading engine: a Java 21 / Spring Boot microservice
pipeline (market data → strategy → execution → order management → post-trade) with two Python
services (ML signal, analytics + portfolio/risk), a React UI, Kafka event backbone, PostgreSQL,
and Redis. The Strategy Engine also hosts a historical-replay backtester.

## Setup

Prerequisites: JDK 21, Python 3.12, Node 22, Docker, [`just`](https://github.com/casey/just);
Helm + OrbStack only for the K8s targets.

```sh
cp .env.example .env    # defaults boot the simulated profile; MARIAALPHA_API_KEY must be non-empty
just py-install         # per-service venvs for ml-signal-service and analytics-service
just ui-install         # npm install in ui/
```

`just check` also expects a repo-root `.venv` with `ruff` and `mypy` (CI pins `ruff==0.15.15`,
`mypy==1.14.1`), activated so the bare `ruff` call resolves.

## Commands

All tasks run through [`just`](justfile). The most-used targets:

| Task | Command |
|---|---|
| Full clean build (auto-fix + UI + Java) | `just build` |
| Bring up the stack (docker compose) | `just run` |
| Verify every service is healthy | `just verify` |
| Tear down | `just stop` |
| All tests (Java unit + Python) | `just test` |
| Java unit tests (excludes `integration` / `e2e` tags) | `just test-java` |
| Java integration tests (Testcontainers; needs Docker) | `just test-integration` |
| End-to-end (boots full stack, ~3–5 min) | `just test-e2e` |
| Python tests | `just test-python` |
| Java + Python lint, format and type checks | `just check` |
| Auto-fix Java + Python formatting and lint | `just fix` |
| UI lint + format check / unit tests / auto-fix | `just ui-lint` / `just ui-test` / `just ui-fix` |
| Mutation testing (slow; CI runs it weekly) | `just mutation` |
| Regenerate gRPC stubs from `proto/` | `just proto` |
| UI dev server / build | `just ui-dev` / `just ui-build` |
| Record the README demo GIF (Playwright tour) | `just demo-up` + `just demo-seed` + `just demo` / `just demo-full` (hermetic) |
| Local K8s (Helm on OrbStack) | `just k8s-up` / `just k8s-test` / `just k8s-down`; `just k8s-lint` needs no cluster |
| Alpaca paper-trading smoke test | `just smoke-alpaca` |

Run `just fix` before `just check` to clear formatting violations automatically. `just check`
does not cover the UI — CI also runs ESLint, Prettier, `tsc --noEmit` and the UI tests, so run
`just ui-lint` and `just ui-test` when touching `ui/`.

## Conventions

- **Java** — Spotless (Google Java Format), Checkstyle, SpotBugs must pass with zero errors; ≥80%
  line coverage (JaCoCo).
- **Python** — `ruff` (lint + format) and `mypy` must pass with zero errors; ≥80% coverage
  (pytest-cov).
- **Integration tests use Testcontainers** — real Kafka / PostgreSQL / Redis, never mocked infra.
- **Plugin extension points** — new behaviour is added by implementing an interface and registering
  it, not by editing existing logic: `TradingStrategy` + `StrategyRegistry` (FR-7), the `RiskCheck`
  chain (FR-24), and the `OrderTypeHandler` registry (FR-22). New strategies/checks need a unit
  test, an integration test, and a `docs/strategies/<name>.md` explainer (mirror an existing one)
  linked from the index in `docs/README.md`.
- **Database changes** — Liquibase changesets under
  `<service>/src/main/resources/db/changelog/changesets/`, registered in that service's
  `db.changelog-master.yaml`. Never edit a changeset that has shipped; add a new one. Migrations
  run on service startup.
- **Kafka topics** — declare new topics in `config/kafka/create-topics.sh`.
- **gRPC** — edit `proto/` and run `just proto`; never hand-edit generated stubs.
- **Git** — direct pushes to `main` are blocked; all changes go through PRs. `Java (lint + test)`
  and `CodeQL (java-kotlin)` must pass before merge. When the work maps to a roadmap item, put its
  ID in the PR title (e.g. `3.5.3 Currency Exposure`): `pr-metadata.yml` uses it to link the issue
  and copy its labels and milestone.

## Safety

- Never commit `.env` or real Alpaca credentials (`ALPACA_API_KEY_ID`, `ALPACA_API_SECRET_KEY`).
- The default `simulated` profiles need no external services. `just smoke-alpaca` and the
  `alpaca` profiles talk to a real (paper) broker — don't run them unless asked.
- Keep `ANALYTICS_REBALANCE_SUBMIT_ENABLED=false` unless asked: when true,
  `POST /api/analytics/portfolio/rebalance/submit` sends a real basket order.

## Repository layout

- Java services: `market-data-gateway/`, `strategy-engine/` (also the backtester,
  `POST /api/backtest`), `execution-engine/`, `order-manager/`, `post-trade/`, `api-gateway/`
- Python services: `ml-signal-service/`, `analytics-service/` (flow toxicity, PnL attribution,
  portfolio construction, firm-wide risk engine)
- `ui/` — Vite + React 18 + TypeScript SPA
- `proto/` — gRPC/protobuf definitions (`just proto` regenerates stubs)
- `config/` — runtime config: `symbols.yml`, `risk-limits.yml`, `portfolio.yml`,
  `stress-scenarios.yml`, plus infra config (kafka, postgres, prometheus, grafana, loki, tempo,
  alloy, checkstyle, spotbugs)
- `charts/mariaalpha/` — umbrella Helm chart; `e2e-tests/` — compose-driven acceptance suite;
  `api-collection/` — Bruno collection of the gateway REST surface; `ml-models/` — `.joblib` model
  artifacts; `scripts/` — demo tape, seeding and GIF tooling

## Documentation

Read on demand — don't load these up front:

- `docs/technical-design-document.md` — top-level architecture, FRs/NFRs, data model, Kafka
  topics, and roadmap. Large (~110 KB): search it for the FR, topic or section you need.
- `docs/README.md` — index linking every per-service deep-dive (`docs/services/`), per-strategy
  explainer (`docs/strategies/`), cross-cutting guide (`docs/guides/`), and runbook
  (`docs/runbooks/`).
- `docs/finance-mental-map.md` — the financial concepts behind the code (order types, execution
  algos, risk checks, options, post-trade). Read it before changing finance-heavy logic.

## When you need more context

- Scan the codebase and read the specific `docs/services/<svc>.md` or `docs/strategies/<name>.md`
  for the area you're touching.
- Ask questions when requirements are ambiguous.
