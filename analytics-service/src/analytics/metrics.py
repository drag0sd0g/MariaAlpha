"""Prometheus metrics for the Analytics Service."""

from prometheus_client import Counter, Gauge, Summary

# 2.2.4 — flow toxicity.
# Summary (not Histogram) because markouts are signed and prometheus_client 0.22 suppresses `_sum`
# on Histograms whose buckets straddle zero — the Grafana mean-markout panel needs `_sum/_count`.
TOXICITY_MARKOUT_BPS = Summary(
    "mariaalpha_analytics_toxicity_markout_bps",
    "Post-fill markout in bps, signed so positive = adverse selection",
    ["strategy", "horizon_seconds"],
)
TOXICITY_ALERTS = Counter(
    "mariaalpha_analytics_toxicity_alerts_total",
    "Number of toxicity alerts emitted",
    ["strategy", "horizon_seconds"],
)

# 2.2.5 — PnL attribution. Summary for the same reason as TOXICITY_MARKOUT_BPS: signed USD values
# require `_sum` to be exposed so the stacked attribution panel can sum per component.
PNL_ATTRIBUTION_USD = Summary(
    "mariaalpha_analytics_pnl_attribution_usd",
    "Per-order PnL attribution in USD, by component",
    ["strategy", "component"],
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
