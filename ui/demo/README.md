# UI Demo Recording

Automated, reproducible recording of the MariaAlpha web UI, used to generate the animated
GIF embedded at the top of the repo `README.md`.

## What's here

| File                                  | Purpose                                                                                        |
| ------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `../playwright.config.ts`             | Playwright config for the recording (video on, single chromium worker, slowMo for legibility). |
| `tour.spec.ts`                        | The guided tour — Strategies → Order Entry → Analytics → Options → Dashboard.                  |
| `.output/`                            | Raw `.webm` recordings + traces (gitignored).                                                  |
| `../../scripts/make-demo-gif.sh`      | Converts the newest `.webm` into `docs/demo/mariaalpha-demo.gif`.                              |
| `../../scripts/generate-demo-tape.py` | Generates the 90-minute drifting replay tape (`config/demo/market-data-demo.csv`, gitignored). |
| `../../scripts/seed-demo-data.sh`     | Seeds axes, strategy bindings, algo parent orders, and order flow; polls until warm.           |
| `../../docker-compose.demo.yml`       | Demo overlay: mounts the generated tape, 10s ML bars, widened TCA arrival lookback.            |

## Run it locally

One-time setup (external fetches — run these yourself):

```bash
brew install ffmpeg
cd ui && npx playwright install chromium
```

Then:

```bash
just demo-up       # generate the demo tape + boot the stack with the demo overlay
just demo-seed     # seed axes, algos, orders; polls until ML + TCA are warm
just demo          # record the tour + regenerate docs/demo/mariaalpha-demo.gif
```

Or hermetically in one shot (clean boot, seed, record, tear down):

```bash
just demo-full
```

> The plain `just run` stack works for the tour too, but its default 4.5-second looping
> tape leaves the ML columns unwarmed and the P&L flat — always record against the demo
> overlay (`just demo-up` / `just demo-full`).

## How it stays robust

- Runs against the **`simulated`** market-data profile replaying a **generated, seeded
  tape** (`just demo-tape`) → identical tape every run, with per-symbol drift so positions
  accrue visible P&L and the ML regime classifier sees real trends.
- `scripts/seed-demo-data.sh` **polls until the stack is demo-ready** (ML regime warmed,
  strategy-labelled TCA rows present, toxicity markouts matured) before the recording
  starts, so no scene lands on a cold panel.
- Every step waits on a **real selector** (the `data-testid`s / `aria-label`s the app already
  ships) — never a fixed sleep keyed to timing. The only `waitForTimeout`s are cosmetic pauses
  so each scene is legible in the GIF.
- Streaming values (prices, P&L) are asserted by **presence, not exact value**, so tick cadence
  and fill latency can't flake the recording. The two content assertions (TSLA row shows POV,
  TCA table shows TWAP) are on states the seed script guarantees.

## Tuning the GIF

`scripts/make-demo-gif.sh` honours env overrides:

| Var           | Default | Effect                                                 |
| ------------- | ------- | ------------------------------------------------------ |
| `DEMO_FPS`    | `12`    | frames/sec (lower = smaller file)                      |
| `DEMO_WIDTH`  | `1200`  | output width in px (height auto)                       |
| `DEMO_MAX_MB` | `8`     | hard size cap — the script fails if the GIF exceeds it |

## Editing the tour

Add or reorder scenes in `tour.spec.ts`. Prefer the existing `data-testid`s; if you need a new
anchor, add the `data-testid` to the component in the same change rather than targeting brittle
text/CSS. Keep total wall-clock under ~45 s so the GIF stays small. Keep the Dashboard as the
final scene — its Daily P&L chart only samples while mounted, so the finale dwell is what draws
the line.
