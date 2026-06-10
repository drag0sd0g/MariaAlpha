# MariaAlpha API Collection (Bruno)

Phase-2 deliverable for issue [2.7.5](https://github.com/drag0sd0g/MariaAlpha/issues/86):
an in-repo Bruno collection covering every public REST endpoint exposed through
the API Gateway plus a handful of direct-to-service requests for debugging.

## Why Bruno?

Bruno is a [Postman-style API client](https://www.usebruno.com/) that stores
collections as plain `.bru` files in the repo — no proprietary export format, no
cloud account, full diff/review in PRs. The TDD picks it over Postman in
[§5.4](../docs/technical-design-document.md) precisely so this collection lives
next to the code it tests.

## Installing Bruno

```bash
brew install bruno          # macOS
# or download a build from https://www.usebruno.com/downloads
```

The CLI runner — handy for smoke-testing requests from a script — is a separate
npm package:

```bash
npm install -g @usebruno/cli
```

## Opening the collection

1. Launch Bruno (the desktop app).
2. **Collections → Open Collection** → pick this `api-collection/` directory.
3. Select an environment from the top-right dropdown:
   - **Local Docker Compose** — gateway at `http://localhost:8080`.
   - **OrbStack Helm** — gateway at `http://api.mariaalpha.orb.local`.
4. The `apiKey` variable defaults to `local-dev-key` (matching the docker-compose
   `.env`). Override per environment as needed.

Run a request with the Send button or with `Cmd-Enter`.

## What's covered

| Folder | Service | Routes |
| --- | --- | --- |
| `00 - Health` | API Gateway | actuator health, readiness, liveness, Prometheus |
| `01 - Strategies` | Strategy Engine | list, state, get/set active, get/update parameters |
| `02 - RFQ` | Strategy Engine | quote, accept, lookup (chained via `lastQuoteId` var) |
| `03 - Orders` | Order Manager | list, by id, fills by date (recon source) |
| `04 - Positions` | Order Manager | list, by symbol |
| `05 - Portfolio` | Order Manager | aggregated summary |
| `06 - Execution` | Execution Engine | status, resume, submit (LIMIT/ICEBERG), cancel, internal crossing |
| `07 - Routing` | Execution Engine | venues, score what-if, decision, venue health |
| `08 - TCA` | Post-Trade | summary, by order |
| `09 - Recon` | Post-Trade | breaks, summary, runs, by order, manual run |
| `10 - Analytics` | Analytics Service | toxicity, PnL attribution, axes (publish/list/cancel/match) |
| `11 - Options` | Strategy Engine | Black-Scholes price+Greeks, Greeks-only, implied volatility |
| `12 - Pegged` | Execution Engine | PEGGED submit (midpoint / primary), pegged-progress |
| `13 - Allocations` | Post-Trade | sub-account roster, run allocation, lookup by order / sub-account |
| `direct-services/` | various | direct (non-gateway) calls for debugging |

The market-data-gateway intentionally has no REST surface — its public API is
gRPC + WebSocket. Use the Strategy Control / Market Data UI pages to exercise it
during manual testing.

**Not yet covered:** the algo execution API (`/api/algo/orders`, see
[`docs/strategies/algo-execution-api.md`](../docs/strategies/algo-execution-api.md))
and the currency-exposure read (`GET /api/portfolio/currency-exposure`, see
[`docs/strategies/currency-exposure.md`](../docs/strategies/currency-exposure.md)).
Both are exercised by curl examples in the root README until folders are added here.

## Conventions

- Every gateway-routed request sends `X-API-Key: {{apiKey}}` — the gateway
  rejects anything without it (except `/actuator/**`).
- Path/query variables that aren't useful by default are commented out with `~`
  (Bruno's "disabled" prefix).
- Requests that produce IDs export them as run-scoped variables (`lastQuoteId`,
  `lastSubmittedOrderId`, `lastIcebergParentId`) so chained calls just work.
- All bodies are JSON unless noted.

## CLI smoke run

Once the stack is up (`just run`), you can fire a single request from CI:

```bash
bru run --env "Local Docker Compose" "00 - Health/Gateway Health.bru"
```

Bruno's CLI returns non-zero on assertion failure, so it slots into a smoke-test
gate without further plumbing.
