#!/usr/bin/env bash
#
# seed-demo-data.sh — generate trading activity so the demo recording's Dashboard and
# Analytics pages are populated instead of empty.
#
# Best-effort: individual calls may fail without aborting the run (no `set -e`). Idempotent
# enough to re-run. Talks to the API gateway with the shared X-API-Key.
#
#   - Binds a few strategies         → Strategy Control rows + per-strategy attribution
#   - Fires MARKET orders            → positions, fills, realized + unrealized P&L, TCA rows,
#                                       flow-toxicity markouts
#   - Leaves resting LIMIT orders    → Active Orders table has content
#   - Publishes a few client axes    → Analytics "Axes" panel has content
#
# Flow-toxicity markouts mature on the (60, 300, 1800)s horizons, so the caller should wait
# ~75 s after seeding before recording for the shortest horizon to populate.
set -uo pipefail

cd "$(dirname "$0")/.."

GW="${DEMO_GW:-http://localhost:8080}"
KEY="${MARIAALPHA_API_KEY:-}"
if [[ -z "$KEY" ]]; then
  KEY="$(grep '^MARIAALPHA_API_KEY=' .env 2>/dev/null | cut -d= -f2)"
fi
KEY="${KEY:-local-dev-key}"

hdr=(-H "X-API-Key: $KEY" -H "Content-Type: application/json")
# The API gateway rate-limits to ~60 req/min per key; space calls out so a burst of seed
# orders never trips it (override with DEMO_SEED_THROTTLE=0 if your gateway is unlimited).
throttle="${DEMO_SEED_THROTTLE:-1.1}"
ok=0
fail=0
note() { printf '  %s\n' "$*"; }
pace() { [[ "$throttle" != "0" ]] && sleep "$throttle"; }

bind() { # symbol strategy
  if curl -fsS -X PUT "${hdr[@]}" -d "{\"strategyName\":\"$2\"}" \
    "$GW/api/strategies/$1" >/dev/null 2>&1; then
    note "bound $1 → $2"
    ok=$((ok + 1))
  else
    fail=$((fail + 1))
  fi
  pace
}

order() { # symbol side type qty [limitPrice]
  local body="{\"symbol\":\"$1\",\"side\":\"$2\",\"orderType\":\"$3\",\"quantity\":$4"
  [[ -n "${5:-}" ]] && body+=",\"limitPrice\":$5"
  body+="}"
  if curl -fsS -X POST "${hdr[@]}" -d "$body" "$GW/api/execution/orders" >/dev/null 2>&1; then
    ok=$((ok + 1))
  else
    fail=$((fail + 1))
  fi
  pace
}

axe() { # axe_id client_id symbol side qty limit
  local body
  body=$(printf '{"axe_id":"%s","client_id":"%s","symbol":"%s","side":"%s","quantity":%s,"limit_price":%s}' \
    "$1" "$2" "$3" "$4" "$5" "$6")
  if curl -fsS -X POST "${hdr[@]}" -d "$body" "$GW/api/analytics/axes" >/dev/null 2>&1; then
    note "axe $1 ($3 $4 $5)"
    ok=$((ok + 1))
  else
    fail=$((fail + 1))
  fi
  pace
}

echo "Seeding demo data → $GW"

echo "[1/4] Binding strategies..."
bind AAPL VWAP
bind MSFT TWAP
bind NVDA MOMENTUM

echo "[2/4] Firing MARKET orders (positions, fills, P&L, TCA, toxicity)..."
# Every order stays under the $100k max-order-notional risk check (prices ~ AAPL 178, MSFT 415,
# GOOGL 156, TSLA 245, AMZN 185, NVDA 430). Accumulate net-long books so unrealized P&L moves
# with the tape (a live Daily P&L line), then trim two names to realize some P&L and add
# two-sided flow for the toxicity markouts.
order AAPL BUY MARKET 400
order AAPL BUY MARKET 400
order MSFT BUY MARKET 150
order MSFT BUY MARKET 150
order GOOGL BUY MARKET 500
order TSLA BUY MARKET 300
order AMZN BUY MARKET 400
order NVDA BUY MARKET 150
order AAPL SELL MARKET 300
order GOOGL SELL MARKET 200

echo "[3/4] Leaving resting LIMIT orders (Active Orders table)..."
order AAPL BUY LIMIT 100 150.00
order MSFT BUY LIMIT 120 200.00
order TSLA SELL LIMIT 90 900.00

echo "[4/4] Publishing client axes (Analytics Axes panel)..."
axe AXE-1001 CLIENT-ALPHA AAPL BUY 5000 null
axe AXE-1002 CLIENT-BRAVO NVDA SELL 3000 null
axe AXE-1003 CLIENT-CARLY MSFT BUY 4000 null

echo "Done — $ok ok, $fail failed."
