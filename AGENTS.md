# MariaAlpha — Agent Context

MariaAlpha is a full-stack algorithmic trading engine: a Java 21 / Spring Boot microservice
pipeline (market data → strategy → execution → order management → post-trade) with two Python
services (ML signal, analytics), a React UI, Kafka event backbone, PostgreSQL, and Redis.

## Commands

All tasks run through [`just`](justfile). The most-used targets:

| Task | Command |
|---|---|
| Full clean build (auto-fix + UI + Java) | `just build` |
| Bring up the stack (docker compose) | `just run` |
| Verify every service is healthy | `just verify` |
| Tear down | `just stop` |
| All tests (Java + Python) | `just test` |
| Java unit + integration (Testcontainers) | `just test-java` |
| Integration only | `just test-integration` |
| End-to-end (boots full stack, ~3–5 min) | `just test-e2e` |
| Python tests | `just test-python` |
| Lint + format check (CI gate) | `just check` |
| Auto-fix formatting + lint | `just fix` |
| Regenerate gRPC stubs from `proto/` | `just proto` |
| UI dev server / build | `just ui-dev` / `just ui-build` |
| Local K8s (Helm on OrbStack) | `just k8s-up` / `just k8s-test` / `just k8s-down` |
| Alpaca paper-trading smoke test | `just smoke-alpaca` |

Run `just fix` before `just check` to clear formatting violations automatically.

## Conventions

- **Java** — Spotless (Google Java Format), Checkstyle, SpotBugs must pass with zero errors; ≥80%
  line coverage (JaCoCo).
- **Python** — `ruff` (lint + format) and `mypy` must pass with zero errors; ≥80% coverage
  (pytest-cov).
- **Integration tests use Testcontainers** — real Kafka / PostgreSQL / Redis, never mocked infra.
- **Plugin extension points** — new behaviour is added by implementing an interface and registering
  it, not by editing existing logic: `TradingStrategy` + `StrategyRegistry` (FR-7), the `RiskCheck`
  chain (FR-24), and the `OrderTypeHandler` registry (FR-22). New strategies/checks need a unit
  test, an integration test, and a `docs/strategies/<name>.md` explainer (mirror an existing one).
- **Git** — direct pushes to `main` are blocked; all changes go through PRs. `Java (lint + test)`
  and `CodeQL (java-kotlin)` must pass before merge.

## Repository layout

- Java services: `market-data-gateway/`, `strategy-engine/`, `execution-engine/`, `order-manager/`,
  `post-trade/`, `api-gateway/`
- Python services: `ml-signal-service/`, `analytics-service/`
- `ui/` — Vite + React 18 + TypeScript SPA
- `proto/` — gRPC/protobuf definitions (`just proto` regenerates stubs)
- `config/` — runtime config: `symbols.yml`, `risk-limits.yml`, plus infra config (kafka, postgres,
  prometheus, grafana, loki, tempo, alloy, checkstyle, spotbugs)
- `charts/mariaalpha/` — umbrella Helm chart; `e2e-tests/` — compose-driven acceptance suite;
  `api-collection/` — Bruno collection of the gateway REST surface; `ml-models/` — `.joblib` model
  artifacts

## Primary reference (always loaded)

The Technical Design Document is the top-level architecture, FRs/NFRs, data model, Kafka topics,
and roadmap.

@import docs/technical-design-document.md

## Documentation index

The index links every per-service deep-dive (`docs/services/`), per-strategy explainer
(`docs/strategies/`), cross-cutting guide (`docs/guides/`), and runbook (`docs/runbooks/`).
**Read the relevant service or strategy doc on demand** when changing that component rather than
loading them all up front.

@import docs/README.md

## When you need more context

- Scan the codebase and read the specific `docs/services/<svc>.md` or `docs/strategies/<name>.md`
  for the area you're touching.
- Ask questions when requirements are ambiguous.
