# MariaAlpha UI — How It Works

## Overview

The UI is a React 18 single-page application that gives traders and portfolio managers a real-time
window into the MariaAlpha system. It is the only human-facing component in the stack. Everything
else — market data ingestion, strategy evaluation, execution, position tracking — runs headlessly;
the UI surfaces the results, accepts manual order entry, and provides a live P&L pulse.

The application connects to the **API Gateway** (port 8080) via REST for snapshots and WebSocket
for streaming updates. It does not communicate directly with any downstream service.

---

## Business Context and User Stories

### Who uses it

The primary persona is a **systematic-trading operator**: someone who monitors the automated
pipeline, intervenes when needed (cancel an order, assess risk), and wants to know at a glance
whether the system is healthy and profitable.

A secondary persona is a **manual trader** who wants to enter orders directly — for instance to
hedge a position that the automated strategies do not cover, or to test how a new symbol flows
through the execution pipeline during development.

### User stories

#### Dashboard stories

| Story | Why it matters |
|-------|----------------|
| As an operator, I want to see total portfolio value, cash balance, net exposure, and total P&L on a single screen so that I can assess the book health without navigating. | Four headline numbers cover the most common monitoring questions. Gross exposure is a risk proxy; cash balance signals whether the system has room to add positions. |
| As an operator, I want a live intraday P&L chart so that I can see whether the book is trending up or down and spot sudden drawdowns. | A time-series view reveals velocity and pattern — a flat line vs. a sharp drop at a specific time tells a different story than a single snapshot number. |
| As an operator, I want to see all open positions sorted by size so that I can quickly identify the largest contributors to risk. | Positions sorted by absolute quantity put the most impactful holdings at the top, where they require no scrolling to find. |
| As an operator, I want position P&L broken into unrealized and realized so that I can understand whether a symbol's total P&L reflects mark-to-market or locked-in gains/losses. | These have different implications: a large unrealized loss is still reversible; a large realized loss is not. |
| As an operator, I want the dashboard to self-heal after a brief network interruption so that I do not need to manually refresh to resume live data. | Trading desks cannot tolerate stale data without knowing it; automatic reconnect prevents silent data gaps. |

#### Order entry stories

| Story | Why it matters |
|-------|----------------|
| As a manual trader, I want to submit MARKET, LIMIT, and STOP orders from the browser so that I can enter positions without using the API directly. | Direct REST calls require API key management and JSON construction; a form with validation is faster and safer for ad-hoc use. |
| As a manual trader, I want client-side validation (symbol required, positive quantity, limit price required for LIMIT orders) so that common mistakes are caught before the network round-trip. | Validation errors from the server surface as generic text; a specific field-level message is faster to act on. |
| As an operator, I want to see all active orders (NEW, SUBMITTED, PARTIALLY_FILLED) in a live table so that I know what is working in the market. | Active orders carry open risk; knowing their status without polling the server means the operator always sees the latest state. |
| As an operator, I want to cancel an order from the browser so that I can exit a position or correct an error without using curl or a separate tool. | Quick cancellation is operationally critical; a one-click action from the same screen where the order appears removes friction. |
| As an operator, I want to see fill history (up to 50 most recent fills) so that I can verify execution quality and that fills are flowing through the pipeline. | Fill history is the proof that the execution engine processed orders correctly and that the simulated/live exchange reported back. |

#### Connection health story

| Story | Why it matters |
|-------|----------------|
| As any user, I want to see a banner when WebSocket feeds are disconnected so that I know live data is stale and I am not making decisions on old numbers. | Silent data staleness is worse than visible data loss; a visible banner is preferable to incorrect confidence. |

---

## Screens

### Dashboard (`/`)

The default landing screen. Loads a snapshot on mount and reconnects to the positions feed.

```
┌─────────────────────────────────────────────────────────────┐
│  [Total Value]  [Cash]  [Net Exposure]  [Total P&L]         │  ← SummaryCards
├─────────────────────────────────────────────────────────────┤
│  Daily P&L ─────────────────────────────────────────────   │  ← DailyPnlChart
│  $0 ────────────────────────────────────── 10 min window   │
├─────────────────────────────────────────────────────────────┤
│  SYMBOL │ QTY │ AVG ENTRY │ MARK │ UNREALIZED │ REALIZED   │  ← PositionsTable
│  AAPL   │ 100 │ $172.40   │ ...  │ ...        │ ...        │
└─────────────────────────────────────────────────────────────┘
```

**Data sources:**
- `GET /api/portfolio/summary` → `PortfolioSummary` (loaded once on mount, reloaded on WS reconnect)
- `GET /api/positions` → `Position[]` (same lifecycle)
- `WS /ws/positions` → `PositionUpdate` stream (drives live position mutations)
- Daily P&L chart samples the Zustand position store every 1 second locally — no extra API call

**SummaryCards** shows four metrics: Total Value, Cash Balance, Net Exposure, and Total P&L.
Null-safe formatting renders `—` until the REST response arrives.

**DailyPnlChart** maintains a 600-sample ring buffer (10 minutes at 1 Hz) using `useRef` to avoid
React re-renders on every tick. A `setInterval` samples `usePositionStore.getState()` directly
(bypassing React's render cycle) and only triggers a render at the end to refresh the chart.
Recharts `ResponsiveContainer` makes the chart fluid. Animation is disabled for performance.

**PositionsTable** reads from the Zustand position store via `useShallow` to avoid re-renders when
unrelated store keys change. Rows are sorted by absolute net quantity (largest position first).
P&L columns are color-coded: green for positive, red for negative, neutral gray for zero.

---

### Order Entry (`/orders`)

Combines order submission with live order monitoring on one screen.

```
┌─────────────────────────────────────────────────────────────┐
│  Submit Order                                               │  ← OrderForm
│  Symbol [AAPL] Side [BUY▼] Type [LIMIT▼] Qty [100]        │
│  Limit Price [___]                                          │
│  [Submit]                                                   │
├─────────────────────────────────────────────────────────────┤
│  Active Orders                                              │  ← ActiveOrdersTable
│  TIME   │ SYMBOL │ SIDE  │ QTY │ TYPE  │ STATUS │ [Cancel] │
│  ...    │ AAPL   │ BUY   │ 100 │ LIMIT │ NEW    │ Cancel   │
├─────────────────────────────────────────────────────────────┤
│  Recent Fills                                               │  ← FillHistoryTable
│  TIME   │ SYMBOL │ SIDE │ QTY │ PRICE │ VENUE             │
└─────────────────────────────────────────────────────────────┘
```

**Data sources:**
- `GET /api/orders?limit=100` → `Order[]` (loaded on mount, reloaded on WS reconnect and after submit)
- `WS /ws/orders` → `OrderEvent` stream (drives live order status updates)
- `GET /api/orders/{id}` → `Order` with fills (fetched per order ID to populate fill history; no dedicated fills endpoint)
- `POST /api/execution/orders` → submit new order
- `DELETE /api/execution/orders/{id}` → cancel order

**OrderForm** is fully controlled. The limit price field conditionally renders only when order type
is LIMIT; stop price only for STOP. Client-side validation runs before any fetch. On success the
form clears quantity/price fields but keeps symbol and side to allow rapid follow-on orders.

**ActiveOrdersTable** filters the order store to statuses `NEW | SUBMITTED | PARTIALLY_FILLED` and
sorts newest-first. The Cancel button fires a DELETE request; the order's status update arrives
shortly after via the `WS /ws/orders` feed, removing it from the active table automatically without
any manual refresh.

**FillHistoryTable** derives its data indirectly: it watches the set of known order IDs, then
fetches each `/api/orders/{id}` in parallel with `Promise.allSettled` (tolerating individual 404s)
and collects the embedded fills. This approach avoids a separate fills endpoint on the backend. To
prevent an infinite re-render loop with Zustand v5's `useSyncExternalStore`, the component derives
a stable string key (`orderIds.sort().join(",")`) rather than comparing Map references.

---

### Planned screens (Phase 2)

The remaining five routes currently render a `ComingSoon` placeholder. Each is listed in the
sidebar already so navigation is stable as they are built out.

| Route | Planned purpose |
|-------|----------------|
| `/market-data` | Live market tick viewer, bid/ask spread, volume; driven by `WS /ws/market-data` |
| `/rfq` | Request-for-quote workflow for block orders where price discovery is needed before committing |
| `/strategies` | Enable/disable individual strategy instances; tune parameters; show last signal timestamp |
| `/analytics` | Transaction cost analysis; slippage vs. VWAP; fill quality over time; driven by TCA methodology |
| `/reconciliation` | End-of-day position reconciliation; compare internal position book against exchange records |

---

## Technical Architecture

### Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Framework | React | 18.3 |
| Build & dev server | Vite | 5.4 |
| Styling | TailwindCSS | 4.0 (Vite plugin, CSS-first) |
| State management | Zustand | 5.0 |
| Charts | Recharts | 2.13 |
| Routing | React Router | 6.30 |
| Language | TypeScript | 5.6 (strict mode) |
| Test runner | Vitest | 2.1 |
| API mocking | MSW | 2.6 |
| Component testing | @testing-library/react | 16.0 |

TypeScript is configured with `strict: true`, `exactOptionalPropertyTypes`,
`noUncheckedIndexedAccess`, and `noImplicitOverride`. The `@` path alias resolves to `src/`.

### Tech stack in depth

This section gives backend engineers a working mental model for each library. It skips
"what a component is" and focuses on the non-obvious design choices and the analogies that
make the tech click for someone who thinks in services, not in DOM trees.

---

#### React 18

React is a declarative view library. A component is a pure function `(props) → JSX`, where
JSX is syntactic sugar for nested `React.createElement(...)` calls — the Vite/SWC plugin
compiles it away before the browser sees it. The runtime re-invokes the function whenever
its local state (via `useState`/`useReducer`) or subscribed external state changes, then
diffs the result against the previous virtual DOM and applies the minimal set of real DOM
mutations.

The v18 upgrade that matters here is **concurrent rendering**: React can interrupt a
low-priority render to handle a high-priority update (e.g., a user click while a large table
re-renders). For MariaAlpha the practical effect is that the `DailyPnlChart` ring-buffer
tick does not block the cancel button from responding.

**Backend analogy**: a component is like a Thymeleaf/FreeMarker template, but the framework
reruns it automatically when its inputs change rather than requiring you to call
`model.addAttribute(...)` again. `useEffect` is the closest equivalent to Spring's
`@EventListener` — it runs side effects (subscribe to WS, start a timer) in response to
lifecycle events.

---

#### Vite 5

Vite is both a **dev server** and a **production bundler**. They are separate modes with
different behaviour:

- **Dev mode** (`npm run dev`): serves source files as native ES modules — the browser
  fetches each file individually over HTTP. There is no upfront bundle step, so cold start
  is near-instant regardless of project size. HMR (Hot Module Replacement) re-executes only
  the changed module and its importers, which is analogous to JRebel hot-swapping a single
  class. The proxy configuration in `vite.config.ts` forwards `/api` and `/ws` to
  `localhost:8080`, making CORS a non-issue — same reason you'd put Nginx in front of a
  backend during development.

- **Production mode** (`npm run build`): runs Rollup under the hood to bundle, tree-shake
  (dead-code eliminate), and fingerprint assets. The output in `ui/dist/` is a handful of
  hashed `.js` and `.css` files plus `index.html`. This is what the `ui/Dockerfile` copies
  into the nginx image.

The SWC compiler (Rust-based, replaces Babel) handles JSX and TypeScript transpilation.
SWC does **not** typecheck — it just strips types. `tsc --noEmit` (run by `npm run build`
before Rollup) is what actually catches type errors.

---

#### TailwindCSS 4

Tailwind is a utility-first CSS framework: instead of writing `.position-table { color: red }`,
you write `className="text-red-500"` directly on the element. The compiler scans source files
for class names and emits only the CSS that is actually used — so the production stylesheet
is typically a few KB.

v4 (used here) moves configuration from `tailwind.config.js` to a CSS-first model: the
theme lives in the root CSS file (`@theme {}` block), and the Vite plugin handles extraction.
There is no separate config file to maintain.

**Backend analogy**: it is the CSS equivalent of Lombok — verbose boilerplate replaced with
concise annotations, at the cost of the HTML being harder to read in isolation.

---

#### Zustand 5

Zustand is a minimal global state store. A store is defined as a plain function that returns
an object of state and updater methods:

```ts
const usePositionStore = create<State>((set) => ({
  positions: new Map(),
  applyUpdate: (u) => set((s) => ({ positions: new Map(s.positions).set(u.symbol, u) })),
}));
```

Components subscribe by calling the hook; they re-render only when the slice they select
changes. Zustand 5 uses React's `useSyncExternalStore` internally, which prevents tearing
under concurrent rendering.

**Backend analogy**: a Zustand store is a thread-safe singleton service (think Spring's
`@Service @Scope("singleton")`) that holds live in-memory state and exposes typed mutation
methods. The three stores here (`positionStore`, `orderStore`, `connectionStore`) are
effectively the UI's in-process cache for data arriving from REST snapshots and WebSocket
streams — the same role a `ConcurrentHashMap` plays in a backend pub-sub consumer.

The key difference from Redux (the older standard): there is no action/reducer/selector
ceremony. Mutations are just method calls; there is no action type string dispatch loop.

---

#### React Router 6

React Router implements **client-side routing** via the browser's History API. Clicking a
`<Link to="/orders">` calls `history.pushState(...)` instead of triggering a full page load.
The router matches the current URL against route definitions and renders the corresponding
component.

v6's `<Outlet />` pattern is the equivalent of Spring MVC's layout templates: `Layout.tsx`
renders the sidebar and nav once; the matched child route fills the `<Outlet/>` slot. Route
definitions live in `App.tsx` and are plain objects, not annotations.

**Backend analogy**: routing is similar to Spring MVC's `@RequestMapping`, except resolution
happens in the browser process rather than the server. The server (nginx in production) must
return `index.html` for every path — hence the `try_files $uri /index.html` directive in
`nginx.conf` — and the browser-side router takes over from there.

---

#### Recharts 2.13

Recharts is a declarative chart library built on top of D3 (the low-level SVG/DOM
manipulation library) and exposed as React components. You describe a chart structurally:

```tsx
<LineChart data={samples}>
  <XAxis dataKey="t" />
  <YAxis />
  <Line type="monotone" dataKey="pnl" dot={false} />
</LineChart>
```

`ResponsiveContainer` wraps any chart to make it fluid — it uses a `ResizeObserver` to
re-render at the container's width. This is why the test setup needs a `ResizeObserver`
polyfill: jsdom does not implement it.

The `DailyPnlChart` deliberately avoids storing the ring buffer in React state. Instead it
uses a `useRef` (a mutable box that does not trigger re-renders on write) and samples
`usePositionStore.getState()` from a `setInterval`, only calling `setState` once per second
to redraw the chart. This is the same pattern as using a background thread to aggregate
metrics before publishing to a gauge.

---

#### Vitest 2.1

Vitest is a unit test runner that runs inside the same Vite pipeline as the application,
meaning the same TypeScript, path aliases, and environment variable handling apply in tests
without extra configuration. The API is intentionally Jest-compatible (`describe`, `it`,
`expect`, `vi.fn()`), so most Jest documentation translates directly.

Tests run in Node.js (via `jsdom` for DOM simulation), not in a real browser. This makes
them fast (no Chrome spawn overhead) but means browser-only APIs (like `ResizeObserver`,
`WebSocket`) must be polyfilled or mocked.

**Backend analogy**: Vitest is JUnit. `jsdom` is the test container that simulates the
browser runtime, analogous to how a Spring Boot test context simulates the application
container.

---

#### MSW 2.6 (Mock Service Worker)

MSW intercepts `fetch` (and `XMLHttpRequest`) calls at the network layer — not by mocking
the `fetch` function on `window`, but by installing a Service Worker in the browser (in
browser mode) or patching Node's HTTP internals (in test mode via `msw/node`).

The interception is transparent to application code: `api.ts` calls `fetch("/api/portfolio/summary")`
exactly as it would against a real server; MSW intercepts the request, matches it against
registered handlers, and returns the mocked response. No application code changes are
needed to enable or disable mocking.

**Backend analogy**: MSW is WireMock. The handler definition style (`http.get("/api/...",
resolver)`) mirrors WireMock's stub DSL. Individual tests override the default handlers for
specific scenarios (empty list, server error) the same way WireMock allows per-test stub
overrides.

---

#### @testing-library/react 16

Testing Library provides query utilities (`getByRole`, `getByText`, `findByLabelText`) and
a `render` function that mounts a component into a minimal DOM and returns those queries.

The core philosophy is to query elements the same way a user would find them — by visible
label, role, or text — rather than by CSS class or component internals. This makes tests
resilient to implementation refactors: renaming a CSS class or splitting a component does
not break the test as long as the rendered output looks the same.

**Backend analogy**: this is the equivalent of writing an `@SpringBootTest` that sends an
HTTP request and asserts the response body, rather than wiring up the service layer directly
and asserting against internal state. Both approaches test the unit from the outside; the
distinction is how much infrastructure is involved.

---

#### Build pipeline end to end

For backend engineers curious how `npm run build` → `ui/dist/` → Docker image → browser
request fits together:

```
TypeScript source (src/)
    │
    ▼ tsc --noEmit        (type errors only; no output)
    ▼ Vite / SWC          (transpile JSX+TS → JS, strip types)
    ▼ Rollup              (tree-shake, code-split, minify)
    ▼
ui/dist/
    ├── index.html        (entry point; references hashed bundles)
    ├── assets/index-[hash].js    (application bundle)
    └── assets/index-[hash].css   (Tailwind output)
    │
    ▼ COPY into nginx image (ui/Dockerfile stage 2)
    ▼
nginx (container, port 5173)
    ├── GET /              → serves index.html (+ Cache-Control: no-cache)
    ├── GET /assets/*      → serves hashed bundles (Cache-Control: immutable, 1y)
    ├── GET /api/*         → proxy_pass http://api-gateway:8080
    └── GET /ws/*          → proxy_pass (WebSocket upgrade) http://api-gateway:8080
```

The hash in bundle filenames is a content hash of the file — identical to how Maven/Gradle
produces deterministic artifact checksums. Because the hash changes with content, browsers
can cache bundles indefinitely (`immutable`) while `index.html` (unhashed) is always
revalidated.

### Directory structure

```
ui/src/
├── App.tsx                  # Router + global ConnectionStatus overlay
├── main.tsx                 # Entry point: StrictMode + BrowserRouter
├── pages/
│   ├── Dashboard.tsx        # Portfolio overview screen
│   └── OrderEntry.tsx       # Order management screen
├── components/
│   ├── Layout.tsx           # Sidebar nav + <Outlet/>
│   ├── ConnectionStatus.tsx # Fixed top banner for WS health
│   ├── SummaryCards.tsx     # 4-metric portfolio header
│   ├── DailyPnlChart.tsx    # 1Hz P&L line chart (ring buffer)
│   ├── PositionsTable.tsx   # Open positions, sorted by size
│   ├── OrderForm.tsx        # MARKET/LIMIT/STOP order entry form
│   ├── ActiveOrdersTable.tsx# Live active orders with cancel
│   ├── FillHistoryTable.tsx # Recent fills fetched from order detail
│   └── ComingSoon.tsx       # Placeholder for Phase 2 screens
├── hooks/
│   └── useWebSocket.ts      # Reconnecting WS hook with exponential backoff
├── stores/
│   ├── connectionStore.ts   # WS endpoint → state map
│   ├── positionStore.ts     # symbol → Position map
│   └── orderStore.ts        # orderId → Order map
├── lib/
│   ├── api.ts               # fetch wrapper; injects X-API-Key header
│   ├── format.ts            # fmtMoney, fmtQty, fmtBps, fmtPnl
│   └── wsUrl.ts             # Builds ws:// URLs; handles Vite proxy vs. direct
├── types/
│   └── api.ts               # TypeScript types mirroring Java DTOs
└── test/
    ├── setup.ts             # MSW start/stop, ResizeObserver polyfill, env stubs
    └── mockServer.ts        # Default MSW request handlers
```

---

## Backend Connectivity

### REST endpoints consumed

All REST calls go through `api()` in [src/lib/api.ts](../ui/src/lib/api.ts). The function:

1. Reads `VITE_MARIAALPHA_API_KEY` from the environment and sets `X-API-Key` header on every
   request. If the key is absent it throws immediately rather than letting the server reject.
2. Sets `Content-Type: application/json` automatically when a body is present.
3. Treats HTTP 204 as a successful void response (used by the cancel endpoint).
4. Throws `ApiError` (with `status` and `path` fields) for non-2xx responses, enabling typed
   error handling in components.

| Method | Path | Used by | Returns |
|--------|------|---------|---------|
| GET | `/api/portfolio/summary` | Dashboard | `PortfolioSummary` |
| GET | `/api/positions` | Dashboard | `Position[]` |
| GET | `/api/orders?limit=100` | OrderEntry | `Order[]` |
| GET | `/api/orders/{id}` | FillHistoryTable | `Order` (with `fills[]`) |
| POST | `/api/execution/orders` | OrderForm | `SubmitOrderResponse` |
| DELETE | `/api/execution/orders/{id}` | ActiveOrdersTable | 204 no body |

The `VITE_API_BASE_URL` variable is optional. When omitted (local dev), paths are relative and the
Vite dev server proxy forwards `/api` and `/ws` to `http://localhost:8080`. In production builds,
`VITE_API_BASE_URL` is set to the API Gateway's base URL.

### WebSocket endpoints consumed

WebSocket URLs are constructed in [src/lib/wsUrl.ts](../ui/src/lib/wsUrl.ts). The API key is
appended as a `?apiKey=...` query parameter because the `Authorization` header cannot be set on
browser WebSocket connections.

| Endpoint | Payload type | Consumed by |
|----------|-------------|-------------|
| `/ws/positions` | `PositionUpdate` | Dashboard |
| `/ws/orders` | `OrderEvent` | OrderEntry |
| `/ws/market-data` | `MarketTick` | Phase 2 (Market Data screen) |
| `/ws/alerts` | `RiskAlert` | Phase 2 (alerts panel) |

The `WsEndpoint` union type in `wsUrl.ts` is the exhaustive list of allowed endpoints; passing any
other string is a compile-time error.

---

## Real-Time Data Architecture

### `useWebSocket` hook

[src/hooks/useWebSocket.ts](../ui/src/hooks/useWebSocket.ts) manages the full lifecycle of a single
WebSocket connection:

- Opens a connection on mount; closes cleanly (code 1000) on unmount.
- Reconnects with **exponential backoff**: starts at 1 second, doubles on each failure, caps at
  30 seconds. The backoff resets to 1 second on a successful open.
- The `onMessage` callback is stored in a `useRef` so the effect never needs to re-run when the
  callback identity changes — avoiding accidental reconnects.
- The `query` parameter is serialized to a stable JSON key for the dependency array, preventing
  re-runs when the caller creates a new object literal on every render.
- Configuration errors (e.g., missing API key in `buildWsUrl`) set the state to `error` without
  retrying, since retrying would never recover.

### `ConnectionStatus` overlay

[src/components/ConnectionStatus.tsx](../ui/src/components/ConnectionStatus.tsx) is rendered
unconditionally in `App.tsx` above the router and sits at `z-50` as a fixed top banner. It reads
from `connectionStore` and applies a 5-second debounce before showing the red "Disconnected" banner,
avoiding flicker during brief transient disconnects. An amber "Reconnecting…" state is shown
immediately when at least one endpoint is attempting to connect.

### Store update patterns

The three Zustand stores use distinct update strategies suited to their data shape:

**positionStore** (`Map<symbol, Position>`): The REST snapshot calls `replaceAll` to overwrite the
entire map. WebSocket `PositionUpdate` messages call `applyUpdate`, which upserts a single entry.
`totalPnl` is not present in the WebSocket payload (it is in `PositionResponse` but not
`PositionSnapshot`), so the store computes it as `realizedPnl + unrealizedPnl` on each update.

**orderStore** (`Map<orderId, Order>`): `OrderEvent` from the WebSocket carries a
`status`, an optional `OrderSnapshot` (full order detail), and an optional `WsFill`. The `applyEvent`
reducer merges these: if the order is already known it updates status; if the snapshot is present it
overwrites with fresh detail; if a fill is present it accumulates `filledQuantity` and updates
`avgFillPrice`. If an event arrives for an unknown order (possible on reconnect), a minimal shell
order is created so the event is not silently dropped.

**connectionStore** (`Record<WsEndpoint, ConnectionState>`): A simple string-value map updated by
`useWebSocket` on every state transition. `remove` is called on hook unmount to clean up stale
entries.

---

## Authentication

Every request (REST and WebSocket) requires `VITE_MARIAALPHA_API_KEY`. The API Gateway validates
this key on all incoming connections. In development the key is set in a `.env.local` file
(excluded from git). In production it is injected at build time via the CI environment.

The application fails fast if the key is absent: `api()` throws `ApiError(0, path, "VITE_MARIAALPHA_API_KEY is not set")` before any network call, and `buildWsUrl` throws synchronously, which `useWebSocket` catches and converts to a permanent `error` state.

---

## Testing

Tests live alongside the source in [src/pages/](../ui/src/pages/) and [src/hooks/](../ui/src/hooks/).

### Approach

**REST API mocking** is handled by [MSW v2](https://mswjs.io/) (Mock Service Worker). The default
handlers in [src/test/mockServer.ts](../ui/src/test/mockServer.ts) return plausible fixture data
for every endpoint. Individual tests can override handlers for specific scenarios (error cases,
empty responses).

**WebSocket mocking** is done with a `FakeWebSocket` class injected into `globalThis.WebSocket`
in the `useWebSocket` tests. This lets tests drive `onopen`, `onmessage`, and `onclose` events
synchronously without a real server.

**Environment stubs** in [src/test/setup.ts](../ui/src/test/setup.ts) set
`VITE_MARIAALPHA_API_KEY` to `test-key` so `api()` and `buildWsUrl()` do not fail during tests.

**ResizeObserver polyfill** is needed because jsdom does not implement it and Recharts'
`ResponsiveContainer` depends on it. The polyfill is installed in `setup.ts`.

### What is tested

| File | Coverage |
|------|---------|
| `useWebSocket.test.ts` | 8 scenarios: open, message delivery, exponential backoff reconnect, clean close on unmount, disabled state, `onMessage` ref stability, error state, query key change triggers reconnect |
| `Dashboard.test.tsx` | Snapshot loads into SummaryCards and PositionsTable; WS message applies live update; error state renders error message |
| `OrderEntry.test.tsx` | Form submits correct payload; validation catches missing symbol; WS OrderEvent updates order list |

Tests run with `npm test` (single run) or `npm run test:watch` (interactive). Coverage is collected
via `@vitest/coverage-v8`.

---

## Development Setup

### Prerequisites

Node.js 20+, npm 10+.

### Environment

Create `ui/.env.local`:

```
VITE_MARIAALPHA_API_KEY=your-key-here
# VITE_API_BASE_URL=    # leave blank to use Vite proxy
```

The Vite proxy ([vite.config.ts](../ui/vite.config.ts)) forwards:
- `/api/**` → `http://localhost:8080` (API Gateway REST)
- `/ws/**` → `ws://localhost:8080` (API Gateway WebSocket, with `ws: true`)

This means the API Gateway must be running (or at least reachable) for real data. The MSW mock
server intercepts requests during tests without touching the network.

### npm scripts

| Script | Purpose |
|--------|---------|
| `npm run dev` | Start Vite dev server on port 5173 (strict — fails if port is taken) |
| `npm run build` | Type-check then produce production bundle in `dist/` |
| `npm run preview` | Serve the `dist/` bundle locally for smoke testing |
| `npm test` | Run all tests once with Vitest |
| `npm run test:watch` | Vitest in watch mode |
| `npm run test:coverage` | Generate V8 coverage report |
| `npm run lint` | ESLint (React Hooks plugin + React Refresh plugin) |
| `npm run lint:fix` | ESLint with auto-fix |
| `npm run format` | Prettier over `src/**/*.{ts,tsx,css}` |
| `npm run format:check` | Prettier check only (used in CI) |

### Production build

`npm run build` emits static files to `ui/dist/`. These can be served from any static host or
bundled into the API Gateway's `resources/static/` directory if a single-binary deployment is
preferred. In the docker-compose stack the UI is served by the Vite dev server on port 5173 via a
`ui` service (or built into a static Nginx container for production use).

---

## Type System

[src/types/api.ts](../ui/src/types/api.ts) defines TypeScript interfaces that mirror every Java DTO
consumed from the backend. The mapping is intentional: field names match exactly (Java's Jackson
uses camelCase by default), and optional fields in the Java record/class are typed as optional in
TypeScript.

Key design decisions:

- **`PortfolioSummary`** mirrors `PortfolioSummaryResponse.java` from post-trade.
- **`PositionUpdate`** (WS) differs from **`Position`** (REST) because the WebSocket payload
  (`PositionSnapshot.java`) does not include `totalPnl`. The store computes it on the fly.
- **`OrderEvent`** carries an optional `OrderSnapshot` (full order detail) and optional `WsFill`
  (fill detail). Both are optional because status-only events (e.g., SUBMITTED) do not include
  them; fill events include both status and fill detail.
- **`WsFill`** is a leaner type than **`Fill`** (REST) — it omits `symbol`, `side`, and `commission`
  which are not present in the Kafka event payload.
- **`WsEndpoint`** is a string union, not a plain `string`. This means mistyped endpoint paths are
  compile errors.

---

## Known Limitations and Phase 2 Notes

- **Fill history is O(n) API calls**: `FillHistoryTable` fetches `/api/orders/{id}` for every
  known order. This works acceptably for tens of orders but would need a dedicated
  `GET /api/fills?limit=50` endpoint at scale.

- **Daily P&L chart resets on page navigation**: The 10-minute ring buffer lives in component
  state, not in the Zustand store. Navigating away and back clears it. Moving it to a persistent
  store or a service worker would retain history across navigation.

- **No pagination on orders table**: The `?limit=100` cap on `GET /api/orders` means high-throughput
  sessions will silently miss older orders. Pagination or a virtual-scroll table would be needed for
  production volumes.

- **Market Data, RFQ, Strategies, Analytics, Reconciliation** screens are `ComingSoon` stubs. The
  routes and sidebar links are wired up; only the page component and any required backend endpoints
  remain to be built.
