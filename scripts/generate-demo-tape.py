#!/usr/bin/env python3
"""Generate the demo replay tape (config/demo/market-data-demo.csv).

The default simulated tape (market-data-gateway/src/main/resources/simulated/market-data.csv)
spans only ~4.5 seconds of tape-time, which is fine for smoke-testing the pipeline but makes a
terrible demo recording: prices never drift (flat P&L), tick timestamps never leave the first
1-minute bar bucket (the ML signal service can never complete a bar, so every signal reads
NEUTRAL 0.00 / UNKNOWN 0.00), and time-windowed algos (VWAP/TWAP/IS) never cross a slice
boundary.

This script writes a richer tape used only by the demo overlay (docker-compose.demo.yml):

  - 90 minutes of tape-time (10:30-12:00 ET), so at the simulated profile's 10x speed
    multiplier one pass lasts ~9 wall-minutes — comfortably longer than seed + record.
  - All six subscribed symbols (the default tape lacks TSLA/AMZN), each with a deliberate
    drift so open positions show live unrealized P&L and the regime classifier sees trends.
  - QUOTE every 5s and TRADE every 15s of tape-time per symbol, so 10-second demo bars
    (ML_SIGNAL_BAR_INTERVAL_SECONDS=10) complete continuously.

Deterministic: fixed RNG seed, so every generated tape is byte-identical. Stdlib only.
Output is gitignored; `just demo-up` / `just demo-full` regenerate it before compose up.
"""

from __future__ import annotations

import random
from datetime import datetime, timedelta, timezone
from pathlib import Path

OUT_PATH = (
    Path(__file__).resolve().parent.parent / "config" / "demo" / "market-data-demo.csv"
)

TAPE_START = datetime(2026, 3, 24, 14, 30, 0, tzinfo=timezone.utc)  # 10:30 ET (EDT)
TAPE_SECONDS = 90 * 60
QUOTE_INTERVAL_S = 5.0
TRADE_INTERVAL_S = 15.0

# symbol -> (base price, total drift over the tape, stagger offset seconds)
# Prices stay in line with scripts/seed-demo-data.sh notional assumptions (every seeded
# order must clear the $100k max-order-notional risk check).
SYMBOLS: dict[str, tuple[float, float, float]] = {
    "AAPL": (178.50, +0.020, 0.0),
    "MSFT": (415.00, -0.008, 0.8),
    "GOOGL": (156.00, +0.012, 1.6),
    "TSLA": (245.00, -0.015, 2.4),
    "AMZN": (185.00, +0.009, 3.2),
    "NVDA": (430.00, +0.032, 4.0),
}

SPREAD_BPS = 2.5
NOISE_BPS = 2.0
MEAN_REVERSION = 0.05

HEADER = "symbol,timestamp,eventType,price,size,bidPrice,askPrice,bidSize,askSize,cumulativeVolume"


def fmt_ts(offset_s: float) -> str:
    ts = TAPE_START + timedelta(seconds=offset_s)
    return ts.strftime("%Y-%m-%dT%H:%M:%S.") + f"{ts.microsecond // 1000:03d}Z"


def generate() -> list[tuple[float, str, str]]:
    rng = random.Random(20260324)
    rows: list[tuple[float, str, str]] = []

    for symbol, (base, drift, stagger) in SYMBOLS.items():
        price = base
        cumulative_volume = 1_000_000
        last_trade_price = base
        n_quotes = int((TAPE_SECONDS - stagger) // QUOTE_INTERVAL_S)

        for i in range(n_quotes):
            t = stagger + i * QUOTE_INTERVAL_S
            progress = t / TAPE_SECONDS

            trendline = base * (1.0 + drift * progress)
            noise = rng.gauss(0.0, NOISE_BPS / 10_000.0) * price
            price = price + noise + MEAN_REVERSION * (trendline - price)
            price = round(max(price, 0.01), 2)

            half_spread = max(round(price * SPREAD_BPS / 2 / 10_000.0, 2), 0.01)
            bid = round(price - half_spread, 2)
            ask = round(price + half_spread, 2)

            is_trade = i % int(TRADE_INTERVAL_S // QUOTE_INTERVAL_S) == 0
            if is_trade:
                size = rng.randrange(50, 400, 10)
                cumulative_volume += size
                last_trade_price = price
                rows.append(
                    (
                        t,
                        symbol,
                        f"{symbol},{fmt_ts(t)},TRADE,{price:.2f},{size},{bid:.2f},{ask:.2f},"
                        f"{rng.randrange(100, 600, 25)},{rng.randrange(100, 600, 25)},{cumulative_volume}",
                    )
                )
            else:
                rows.append(
                    (
                        t,
                        symbol,
                        f"{symbol},{fmt_ts(t)},QUOTE,{last_trade_price:.2f},0,{bid:.2f},{ask:.2f},"
                        f"{rng.randrange(100, 600, 25)},{rng.randrange(100, 600, 25)},{cumulative_volume}",
                    )
                )

    rows.sort(key=lambda r: (r[0], r[1]))
    return rows


def main() -> None:
    rows = generate()
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUT_PATH.open("w", encoding="utf-8", newline="\n") as f:
        f.write(HEADER + "\n")
        for _, _, line in rows:
            f.write(line + "\n")
    span_min = TAPE_SECONDS / 60
    print(
        f"Wrote {len(rows)} ticks ({span_min:.0f} min of tape-time, {len(SYMBOLS)} symbols) -> {OUT_PATH}"
    )


if __name__ == "__main__":
    main()
