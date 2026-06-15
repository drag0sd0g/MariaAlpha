"""Prometheus metrics for the Analytics Service."""

from prometheus_client import Counter, Gauge, Summary

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

PNL_ATTRIBUTION_USD = Summary(
    "mariaalpha_analytics_pnl_attribution_usd",
    "Per-order PnL attribution in USD, by component",
    ["strategy", "component"],
)

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
