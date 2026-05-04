# Alpaca Paper-Trading Smoke Test

This runbook is the manual verification for issue
[#51 — End-to-end smoke test with Alpaca paper trading](https://github.com/drag0sd0g/MariaAlpha/issues/51).

It is run **once per release branch**, not on every PR. The simulated end-to-end test
(issue #50) gates merges; this runbook proves the same pipeline works against a real
broker.

## Prerequisites

1. **Alpaca paper-trading account** — sign up at <https://alpaca.markets> (free).
2. **API keys** — generate a paper-trading key pair from the dashboard.
3. **`just`, Docker, ~8 GB free RAM**.
4. **US market open** — Alpaca paper streams real IEX market data, which is sparse
   outside US equity market hours (09:30–16:00 ET, Mon-Fri). Run the smoke test
   during these hours or you will see no ticks.

## Steps

### 1. Configure credentials
```bash
cp .env.example .env
# Edit .env and set:
#   ALPACA_API_KEY_ID=<paper-key-id>
#   ALPACA_API_SECRET_KEY=<paper-secret>
#   MARKET_DATA_PROFILE=alpaca
#   EXECUTION_PROFILE=alpaca
#   MARIAALPHA_API_KEY=<any-string,-e.g.-local-dev-key>
```
### 2. Start the stack
```bash
just smoke-alpaca
```
This recipe is equivalent to `just build && just run`, with `MARKET_DATA_PROFILE` and 
`EXECUTION_PROFILE` forced to alpaca. Wait until docker compose ps shows all services healthy, 
typically ~90 seconds.

Verify the gateway:
```bash
curl -fsS -H "X-API-Key: $MARIAALPHA_API_KEY" \
    http://localhost:8080/actuator/health/readiness
# {"status":"UP"}
```

### 3. Confirm ticks are flowing

Open Grafana at http://localhost:3001, dashboard `MariaAlpha → Trading Pipeline`. 
The `mariaalpha_md_ticks_received_total` counter should be increasing for at least one symbol 
from `config/symbols.yml`. If the counter is flat, you are likely outside US market hours. Stop and try 
again during the session.

### 4. Place a manual LIMIT order
```bash
curl -X POST -H "X-API-Key: $MARIAALPHA_API_KEY" \
    -H "Content-Type: application/json" \
    -d '{
          "symbol": "AAPL",
          "side": "BUY",
          "orderType": "LIMIT",
          "quantity": 1,
          "limitPrice": 100.00
        }' \
    http://localhost:8080/api/execution/orders
```
The limit price `$100` is intentionally far below market for `AAPL`. The order will sit on Alpaca's paper book; we are 
testing the placement path, not the fill path (see Step 5 for fill). Capture the response — it includes the orderId. 
Save it for Step 6.

### 5. Place a marketable LIMIT order
```bash
# First, peek at the current AAPL bid/ask via the gateway to choose a marketable price:
curl -fsS -H "X-API-Key: $MARIAALPHA_API_KEY" \
    http://localhost:8080/api/market-data/quote/AAPL

# Then place a BUY LIMIT at or above the current ask:
curl -X POST -H "X-API-Key: $MARIAALPHA_API_KEY" \
    -H "Content-Type: application/json" \
    -d '{
          "symbol": "AAPL",
          "side": "BUY",
          "orderType": "LIMIT",
          "quantity": 1,
          "limitPrice": <ask + 0.05>
        }' \
    http://localhost:8080/api/execution/orders
```

### 6. Verify fill, position, P&L
```bash
# Fill arrives via Alpaca's trade_updates WebSocket stream and is consumed by
# execution-engine, then republished on orders.lifecycle for order-manager.
# Allow ~5 s for the round trip.

sleep 10
curl -fsS -H "X-API-Key: $MARIAALPHA_API_KEY" \
    http://localhost:8080/api/orders/<orderId> | jq
# Expected: status == "FILLED", fills array non-empty.

curl -fsS -H "X-API-Key: $MARIAALPHA_API_KEY" \
    http://localhost:8080/api/positions/AAPL | jq
# Expected: netQuantity == 1, avgEntryPrice ≈ ask price.

curl -fsS -H "X-API-Key: $MARIAALPHA_API_KEY" \
    http://localhost:8080/api/portfolio/summary | jq
# Expected: openPositions == 1, totalPnl != null.
```

### 7. Cancel the resting order from Step 4
```bash
curl -X DELETE -H "X-API-Key: $MARIAALPHA_API_KEY" \
    http://localhost:8080/api/execution/orders/<resting-orderId>
# 204 No Content
```

### 8. Cross-check on Alpaca's dashboard
Sign into https://app.alpaca.markets. Under "Paper Trading → Activity" you should see the two orders (one filled, one 
cancelled). The position blotter should show 1 share long AAPL.

### 9. Tear down
```bash
just stop
```