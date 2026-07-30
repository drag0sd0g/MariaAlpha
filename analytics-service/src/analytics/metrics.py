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

# --- Roadmap 4.6.1 — portfolio construction & risk ---

PORTFOLIO_VAR_USD = Gauge(
    "mariaalpha_analytics_portfolio_var_usd",
    "Portfolio Value-at-Risk in USD",
    ["method", "confidence"],
)
PORTFOLIO_ES_USD = Gauge(
    "mariaalpha_analytics_portfolio_expected_shortfall_usd",
    "Portfolio expected shortfall (conditional VaR) in USD",
    ["method", "confidence"],
)
DIVERSIFICATION_RATIO = Gauge(
    "mariaalpha_analytics_diversification_ratio",
    "Sum-of-absolutes VaR divided by covariance VaR; 1.0 means no diversification credit",
)
COVARIANCE_OBSERVATIONS = Gauge(
    "mariaalpha_analytics_covariance_observations",
    "Number of aligned bars behind the current covariance estimate",
)
COVARIANCE_SHRINKAGE = Gauge(
    "mariaalpha_analytics_covariance_shrinkage_intensity",
    "Ledoit-Wolf shrinkage intensity applied to the sample covariance",
)
OPTIMIZER_RUNS = Counter(
    "mariaalpha_analytics_optimizer_runs_total",
    "Portfolio optimiser invocations",
    ["objective", "converged"],
)
OPTIMIZER_LATENCY = Summary(
    "mariaalpha_analytics_optimizer_seconds",
    "Portfolio optimiser wall time in seconds",
    ["objective"],
)
STRESS_BREACHES = Counter(
    "mariaalpha_analytics_stress_breaches_total",
    "Stress scenarios whose loss breached the configured limit",
    ["scenario"],
)
RISK_MODEL_PUBLISHED = Counter(
    "mariaalpha_analytics_risk_model_published_total",
    "Covariance risk models published to the analytics.risk-model topic",
)
