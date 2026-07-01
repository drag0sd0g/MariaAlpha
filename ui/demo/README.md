# UI Demo Recording

Automated, reproducible recording of the MariaAlpha web UI, used to generate the animated
GIF embedded at the top of the repo `README.md`.

## What's here

| File                             | Purpose                                                                                        |
| -------------------------------- | ---------------------------------------------------------------------------------------------- |
| `../playwright.config.ts`        | Playwright config for the recording (video on, single chromium worker, slowMo for legibility). |
| `tour.spec.ts`                   | The guided tour — Dashboard → Strategies → Order Entry → Dashboard → Analytics.                |
| `.output/`                       | Raw `.webm` recordings + traces (gitignored).                                                  |
| `../../scripts/make-demo-gif.sh` | Converts the newest `.webm` into `docs/demo/mariaalpha-demo.gif`.                              |

## Run it locally

One-time setup (external fetches — run these yourself):

```bash
brew install ffmpeg
cd ui && npx playwright install chromium
```

Then, with the stack already up and healthy (`just run`):

```bash
just demo          # record the tour + regenerate docs/demo/mariaalpha-demo.gif
```

Or hermetically (boots the stack, records, tears it down):

```bash
just demo-full
```

## How it stays robust

- Runs against the default **`simulated`** market-data profile (CSV replay) → identical tape
  every run.
- Every step waits on a **real selector** (the `data-testid`s / `aria-label`s the app already
  ships) — never a fixed sleep keyed to timing. The only `waitForTimeout`s are cosmetic pauses
  so each scene is legible in the GIF.
- Streaming values (prices, P&L) are asserted by **presence, not exact value**, so tick cadence
  and fill latency can't flake the recording.

## Tuning the GIF

`scripts/make-demo-gif.sh` honours env overrides:

| Var           | Default | Effect                                                 |
| ------------- | ------- | ------------------------------------------------------ |
| `DEMO_FPS`    | `15`    | frames/sec (lower = smaller file)                      |
| `DEMO_WIDTH`  | `1280`  | output width in px (height auto)                       |
| `DEMO_MAX_MB` | `8`     | hard size cap — the script fails if the GIF exceeds it |

## Editing the tour

Add or reorder scenes in `tour.spec.ts`. Prefer the existing `data-testid`s; if you need a new
anchor, add the `data-testid` to the component in the same change rather than targeting brittle
text/CSS. Keep total wall-clock under ~40 s so the GIF stays small.
