# Algo Execution API & WebSocket

> **Roadmap:** [3.4.4 — Electronic Trading REST API](https://github.com/drag0sd0g/MariaAlpha/issues/100), [3.4.5 — WebSocket execution tracking](https://github.com/drag0sd0g/MariaAlpha/issues/119).
> **TDD reference:** §3.2 (Strategy Engine), §3.9 (API Gateway), §5.2.2 (Strategy Engine service descriptor).

## 1. What this is

A programmatic surface to submit, query, list, and cancel algorithmic parent orders without going through the symbol-binding REST flow (`PUT /api/strategies/{symbol}` + `PUT /api/strategies/{name}/parameters`). One POST creates the parent and binds the strategy in one shot; a UUID comes back that the caller can poll, cancel, and (via the companion WebSocket) follow in real time.

Lives on the API Gateway, routes to the Strategy Engine. Lifecycle events fan out via the new `algo.progress` Kafka topic to the `/ws/algo` WebSocket endpoint.

## 2. REST surface

| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| `POST` | `/api/algo/orders` | `AlgoOrderRequest` | `201 Created` + `AlgoOrderResponse` |
| `GET`  | `/api/algo/orders/{id}` | — | `200 OK` + `AlgoOrderResponse` / `404` |
| `GET`  | `/api/algo/orders` | — | `200 OK` + array |
| `DELETE` | `/api/algo/orders/{id}` | — | `200 OK` + the cancelled `AlgoOrderResponse` / `404` |

### `AlgoOrderRequest`

```json
{
  "symbol": "MSFT",
  "side": "BUY",
  "targetQuantity": 50,
  "strategyName": "TWAP",
  "parameters": {
    "targetQuantity": 50,
    "side": "BUY",
    "startTime": "10:30:00",
    "endTime": "10:31:00",
    "numSlices": 1
  }
}
```

`parameters` is forwarded verbatim to `strategy.updateParameters` — the shape must match what the strategy expects. Each strategy's parameter contract is documented in its own page (`docs/strategies/vwap.md`, `twap.md`, etc.).

### `AlgoOrderResponse`

```json
{
  "algoOrderId": "f5d8e4c1-3a8c-4c1f-9a3b-2a0b1c8b1a23",
  "symbol": "MSFT",
  "side": "BUY",
  "targetQuantity": 50,
  "strategyName": "TWAP",
  "parameters": { "...": "..." },
  "status": "ACTIVE",
  "createdAt": "2026-06-09T05:42:11.034Z",
  "updatedAt": "2026-06-09T05:42:11.034Z"
}
```

`status` is one of `ACTIVE`, `CANCELLED`, `COMPLETED`. v1 only emits `ACTIVE` and `CANCELLED`; `COMPLETED` is reserved for the future-work item that wires filled-quantity tracking.

### Errors

| Condition | Status | Body |
| --- | --- | --- |
| `strategyName` not registered | `400` | `Unknown strategy: ...` |
| Validation failure (missing `symbol`, `targetQuantity ≤ 0`) | `400` | Spring validation error |
| Unknown algo order id on GET / DELETE | `404` | empty |

## 3. WebSocket: `/ws/algo`

The companion stream. Open a WS connection to `wss://<gateway>/ws/algo` (with the same API-key auth used elsewhere) to receive a JSON event each time an algo order's lifecycle advances **or** a signal fires for an active algo order:

```json
{
  "algoOrderId": "f5d8e4c1-3a8c-4c1f-9a3b-2a0b1c8b1a23",
  "eventType": "SIGNAL_EMITTED",
  "symbol": "MSFT",
  "parentSide": "BUY",
  "targetQuantity": 50,
  "strategyName": "TWAP",
  "status": "ACTIVE",
  "signalSide": "BUY",
  "signalQuantity": 50,
  "signalLimitPrice": 415.24,
  "timestamp": "2026-06-09T05:42:14.105Z"
}
```

Event types currently emitted:

| `eventType` | Trigger |
| --- | --- |
| `CREATED` | `POST /api/algo/orders` succeeded |
| `SIGNAL_EMITTED` | Bound strategy published an `OrderSignal` for the algo's symbol |
| `CANCELLED` | `DELETE /api/algo/orders/{id}` |
| `COMPLETED` | (Reserved — see future work) |

Filter on `algoOrderId` to track a single parent.

## 4. How signal correlation works

When the strategy bound to an algo order publishes a signal, `SignalPublisher` consults the in-memory `AlgoOrderRegistry` for any `ACTIVE` algo orders matching the signal's symbol and emits a `SIGNAL_EMITTED` event per match. Best-effort: a serialization failure on the algo.progress topic does not block the signal itself from being published to `strategy.signals`.

If two algo orders are simultaneously active on the same symbol (e.g. caller submitted two competing TWAPs by mistake), both will receive `SIGNAL_EMITTED` events for every signal — the signal itself is a single physical child order, but the WebSocket reflects which parents would have "wanted" it.

## 5. Where it lives

- Domain model — [`strategy-engine/.../algo/AlgoOrder.java`](../../strategy-engine/src/main/java/com/mariaalpha/strategyengine/algo/AlgoOrder.java)
- Registry — [`strategy-engine/.../algo/AlgoOrderRegistry.java`](../../strategy-engine/src/main/java/com/mariaalpha/strategyengine/algo/AlgoOrderRegistry.java)
- Service — [`strategy-engine/.../algo/AlgoOrderService.java`](../../strategy-engine/src/main/java/com/mariaalpha/strategyengine/algo/AlgoOrderService.java)
- Controller — [`strategy-engine/.../algo/AlgoOrderController.java`](../../strategy-engine/src/main/java/com/mariaalpha/strategyengine/algo/AlgoOrderController.java)
- Progress publisher — [`strategy-engine/.../algo/AlgoProgressPublisher.java`](../../strategy-engine/src/main/java/com/mariaalpha/strategyengine/algo/AlgoProgressPublisher.java)
- WebSocket bridge — [`api-gateway/.../websocket/KafkaTopicBroadcaster.java`](../../api-gateway/src/main/java/com/mariaalpha/apigateway/websocket/KafkaTopicBroadcaster.java)
- Gateway route — `api-gateway/src/main/resources/application.yml` (`strategies` route path predicate)
- Kafka topic — `config/kafka/create-topics.sh` (`algo.progress`)

## 6. Future work

- **Filled-quantity progress** — currently `targetQuantity` is informational only; `COMPLETED` is never emitted. To wire real progress, propagate `algoOrderId` through `OrderSignal` → execution-engine `ExecutionInstruction` → order-manager `OrderEntity.parentOrderId`. Then the `orders.lifecycle` consumer in strategy-engine can correlate fills back to algo orders and emit `FILLED` / `COMPLETED` events.
- **Persistence** — algo orders are in-memory today. Survives single-replica restarts via the existing strategy-binding state, but multi-replica HA needs a Postgres-backed registry.
- **Algo-level risk policy** — separate from the execution-engine pre-trade chain, a desk may want algo-level guards (e.g. "no more than 3 active algos on the same symbol", "max algo target notional per minute"). Natural fit alongside `AlgoOrderService`.
- **Algo modifications** — `PATCH /api/algo/orders/{id}` to change parameters mid-flight (e.g. tighten TWAP's end-time on a fast market). Maps to the existing `updateParameters` hook on the bound strategy.
