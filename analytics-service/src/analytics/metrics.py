"""Prometheus metrics for the Analytics Service."""

from prometheus_client import Counter, Gauge, Histogram

# 2.2.4 — flow toxicity
TOXICITY_MARKOUT_BPS = Histogram(
    "mariaalpha_analytics_toxicity_markout_bps",
    "Post-fill markout in bps, signed so positive = adverse selection",
    ["strategy", "horizon_seconds"],
    buckets=[-50, -25, -10, -5, -2, -1, 0, 1, 2, 5, 10, 25, 50],
)
TOXICITY_ALERTS = Counter(
    "mariaalpha_analytics_toxicity_alerts_total",
    "Number of toxicity alerts emitted",
    ["strategy", "horizon_seconds"],
)

# 2.2.5 — PnL attribution
PNL_ATTRIBUTION_USD = Histogram(
    "mariaalpha_analytics_pnl_attribution_usd",
    "Per-order PnL attribution in USD, by component",
    ["strategy", "component"],
    buckets=[-1000, -500, -250, -100, -50, -10, 0, 10, 50, 100, 250, 500, 1000],
)

# 2.2.6 — axes
AXES_ACTIVE = Gauge(
    "mariaalpha_analytics_axes_active",
    "Number of active (non-expired) axes",
    ["symbol", "side"],
)
AXES_MATCHES = Counter(
    "mariaalpha_analytics_axes_matches_total",
    "Axe matches suggested for incoming orders",
    ["symbol", "match_quality"],
)
