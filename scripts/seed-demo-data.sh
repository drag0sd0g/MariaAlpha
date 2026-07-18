#!/usr/bin/env bash
#
# seed-demo-data.sh — generate trading activity so the demo recording's Dashboard, Strategy
# Control, and Analytics pages are populated instead of empty.
#
# Best-effort: individual calls may fail without aborting the run (no `set -e`). Idempotent
# enough to re-run. Talks to the API gateway with the shared X-API-Key. Designed to run
# against the demo overlay stack (`just demo-up`), whose 90-minute drifting tape makes the
# seeded positions accrue visible unrealized P&L and warms the ML signal/regime models.
#
#   - Publishes client axes FIRST      → the axe matcher only matches *subsequent* opposite-
#                                        side order flow, so axes must exist before orders
#   - Binds + configures MOMENTUM      → NVDA emits organic strategy signals off the tape
#   - Submits algo parent orders       → TWAP + VWAP slices fill with a strategy label, so
#     (POST /api/algo/orders)            TCA shows per-strategy rows + VWAP benchmark
#   - Fires MARKET orders              → positions, fills, realized + unrealized P&L, TCA
#                                        rows, flow-toxicity markouts, axe matches
#   - Leaves resting LIMIT orders      → Active Orders table has content
#   - Polls until ML + TCA are ready   → the recording never starts against cold panels
#
# Flow-toxicity markouts mature on the (60, 300, 1800)s horizons; the final wait covers the
# shortest horizon (override with DEMO_SEED_WAIT).
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

params() { # strategyName json-body
  if curl -fsS -X PUT "${hdr[@]}" -d "$2" \
    "$GW/api/strategies/$1/parameters" >/dev/null 2>&1; then
    note "configured $1"
    ok=$((ok + 1))
  else
    fail=$((fail + 1))
  fi
  pace
}

algo() { # json-body label
  if curl -fsS -X POST "${hdr[@]}" -d "$1" "$GW/api/algo/orders" >/dev/null 2>&1; then
    note "algo order: $2"
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

# Poll an endpoint until its body matches (or stops matching) a pattern, capped at a deadline.
wait_for() { # description timeout_s url grep_args...
  local desc="$1" timeout="$2" url="$3"
  shift 3
  local deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    if curl -fsS -H "X-API-Key: $KEY" "$url" 2>/dev/null | grep -q "$@"; then
      note "$desc — ready"
      return 0
    fi
    sleep 3
  done
  note "$desc — NOT ready after ${timeout}s (recording will proceed anyway)"
  return 1
}

echo "Seeding demo data → $GW"

echo "[1/6] Publishing client axes (must precede order flow so the matcher can match)..."
axe AXE-1001 CLIENT-ALPHA AAPL BUY 5000 null
axe AXE-1002 CLIENT-BRAVO NVDA SELL 3000 null
axe AXE-1003 CLIENT-CARLY MSFT BUY 4000 null

echo "[2/6] Binding + configuring MOMENTUM on NVDA (organic signals off the trending tape)..."
bind NVDA MOMENTUM
params MOMENTUM '{
  "fastPeriod": 20,
  "slowPeriod": 50,
  "rsiPeriod": 14,
  "rsiOverbought": 70,
  "rsiOversold": 30,
  "volumeMultiplier": 1.5,
  "tradeQuantity": 100,
  "side": "BUY",
  "stopLossPct": 2.0
}'

# The demo tape replays 10:30–12:00 ET; strategies window on TAPE time (tick timestamps),
# so both algo windows span the whole tape. Wherever the replay currently is, the slice
# containing "now" fires immediately and later slices keep firing as the tape advances.
echo "[3/6] Submitting algo parent orders (strategy-labelled fills for TCA + attribution)..."
algo '{
  "symbol": "MSFT",
  "side": "BUY",
  "targetQuantity": 600,
  "strategyName": "TWAP",
  "parameters": {
    "targetQuantity": 600,
    "side": "BUY",
    "startTime": "10:30:00",
    "endTime": "12:00:00",
    "numSlices": 30
  }
}' "MSFT TWAP 600 (30 slices, 10:30–12:00 ET)"
algo '{
  "symbol": "AAPL",
  "side": "BUY",
  "targetQuantity": 600,
  "strategyName": "VWAP",
  "parameters": {
    "targetQuantity": 600,
    "side": "BUY",
    "startTime": "10:30:00",
    "endTime": "12:00:00",
    "volumeProfile": [
      {"startTime":"10:30:00","endTime":"10:45:00","volumeFraction":0.1666},
      {"startTime":"10:45:00","endTime":"11:00:00","volumeFraction":0.1666},
      {"startTime":"11:00:00","endTime":"11:15:00","volumeFraction":0.1666},
      {"startTime":"11:15:00","endTime":"11:30:00","volumeFraction":0.1666},
      {"startTime":"11:30:00","endTime":"11:45:00","volumeFraction":0.1666},
      {"startTime":"11:45:00","endTime":"12:00:00","volumeFraction":0.167}
    ]
  }
}' "AAPL VWAP 600 (6 bins, 10:30–12:00 ET)"

echo "[4/6] Firing MARKET orders (positions, fills, P&L, TCA, toxicity, axe matches)..."
# Every order stays under the $100k max-order-notional risk check (tape prices ~ AAPL 178,
# MSFT 415, GOOGL 156, TSLA 245, AMZN 185, NVDA 430). Accumulate net-long books so
# unrealized P&L moves with the drifting tape (a live Daily P&L line), then trim three
# names to realize some P&L, add two-sided flow for the toxicity markouts, and hit the
# opposite side of every axe published in [1/6] so "Matched lifetime" is non-zero.
order AAPL BUY MARKET 400
order AAPL BUY MARKET 400
order MSFT BUY MARKET 150
order MSFT BUY MARKET 150
order GOOGL BUY MARKET 500
order TSLA BUY MARKET 300
order AMZN BUY MARKET 400
order NVDA BUY MARKET 150 # matches AXE-1002 (NVDA SELL axe)
order AAPL SELL MARKET 300 # matches AXE-1001 (AAPL BUY axe)
order MSFT SELL MARKET 50 # matches AXE-1003 (MSFT BUY axe)
order GOOGL SELL MARKET 200
markouts_started=$SECONDS

echo "[5/6] Leaving resting LIMIT orders (Active Orders table)..."
order AAPL BUY LIMIT 100 150.00
order MSFT BUY LIMIT 120 200.00
order TSLA SELL LIMIT 90 900.00

echo "[6/6] Waiting for analytics to mature before recording..."
# ML columns: every bound symbol should classify a real regime (needs 60 completed bars —
# ~60s of wall-clock from stack start with the demo overlay's 10s bars at 10x replay).
wait_for "ML regime warm-up" 150 "$GW/api/strategies/state" -v UNKNOWN
# TCA: at least one strategy-labelled (TWAP) algo fill has flowed through post-trade.
wait_for "TCA strategy-labelled rows" 120 "$GW/api/tca" TWAP
# Flow toxicity: shortest markout horizon (60s) must elapse after the last MARKET order.
markout_wait="${DEMO_SEED_WAIT:-75}"
elapsed=$((SECONDS - markouts_started))
if ((elapsed < markout_wait)); then
  note "waiting $((markout_wait - elapsed))s more for 60s toxicity markouts to mature..."
  sleep $((markout_wait - elapsed))
fi

echo "Done — $ok ok, $fail failed."
