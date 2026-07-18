#!/usr/bin/env bash
#
# make-demo-gif.sh — convert the newest Playwright tour recording into the README GIF.
#
# Input:  the most recent .webm produced under ui/demo/.output/ by `npx playwright test`.
# Output: docs/demo/mariaalpha-demo.gif  (committed, embedded in README.md)
#
# Requires ffmpeg on PATH. Two-pass palettegen/paletteuse keeps the GIF sharp and small.
# Tunables below; a hard size cap fails the build if the GIF balloons.
set -euo pipefail

cd "$(dirname "$0")/.."

# ── Tunables ────────────────────────────────────────────────────────────────────────────
FPS="${DEMO_FPS:-12}"
WIDTH="${DEMO_WIDTH:-1200}"
MAX_MB="${DEMO_MAX_MB:-8}"
OUT_DIR="docs/demo"
GIF="$OUT_DIR/mariaalpha-demo.gif"
SRC_DIR="ui/demo/.output"

command -v ffmpeg >/dev/null 2>&1 || {
  echo "error: ffmpeg not found on PATH (brew install ffmpeg)" >&2
  exit 1
}

IN="$(find "$SRC_DIR" -name '*.webm' -print0 2>/dev/null | xargs -0 ls -t 2>/dev/null | head -1 || true)"
if [[ -z "${IN:-}" ]]; then
  echo "error: no .webm recording found under $SRC_DIR — run the tour first (just demo)" >&2
  exit 1
fi
echo "Source recording: $IN"

mkdir -p "$OUT_DIR"
PAL="$(mktemp -t demo-palette-XXXXXX).png"
trap 'rm -f "$PAL"' EXIT

# Pass 1 — derive an optimal 256-colour palette from the whole clip.
ffmpeg -y -loglevel error -i "$IN" \
  -vf "fps=${FPS},scale=${WIDTH}:-1:flags=lanczos,palettegen=stats_mode=diff" "$PAL"

# Pass 2 — render the GIF with that palette, looping forever.
ffmpeg -y -loglevel error -i "$IN" -i "$PAL" \
  -lavfi "fps=${FPS},scale=${WIDTH}:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=3" \
  -loop 0 "$GIF"

# ── Report + size guard ──────────────────────────────────────────────────────────────────
gif_bytes=$(wc -c <"$GIF" | tr -d ' ')
gif_mb=$(awk -v b="$gif_bytes" 'BEGIN { printf "%.1f", b / 1048576 }')
echo "Wrote $GIF (${gif_mb} MB)"

if awk -v b="$gif_bytes" -v cap="$MAX_MB" 'BEGIN { exit !(b > cap * 1048576) }'; then
  echo "error: GIF is ${gif_mb} MB, over the ${MAX_MB} MB cap." >&2
  echo "       Trim the tour, lower DEMO_FPS, or reduce DEMO_WIDTH and re-run." >&2
  exit 1
fi
