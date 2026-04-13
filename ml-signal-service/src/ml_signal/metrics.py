"""Prometheus metrics for the ML Signal Service."""

from prometheus_client import Counter, Gauge, Histogram, Info

INFERENCE_DURATION = Histogram(
    "mariaalpha_ml_inference_duration_seconds",
    "Model inference latency",
    ["model"],
    buckets=[0.0005, 0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25],
)

FEATURE_STALENESS = Gauge(
    "mariaalpha_ml_feature_staleness_seconds",
    "Seconds since last feature update for a symbol",
    ["symbol"],
)

TICKS_CONSUMED = Counter(
    "mariaalpha_ml_ticks_consumed_total",
    "Total ticks consumed from Kafka",
    ["symbol", "event_type"],
)

FEATURES_COMPUTED = Counter(
    "mariaalpha_ml_features_computed_total",
    "Total feature vectors computed",
    ["symbol"],
)

MODEL_INFO = Info(
    "mariaalpha_ml_model",
    "Currently loaded model metadata",
)

GRPC_REQUESTS = Counter(
    "mariaalpha_ml_grpc_requests_total",
    "gRPC requests by method",
    ["method"],
)

STREAM_CLIENTS_ACTIVE = Gauge(
    "mariaalpha_ml_stream_clients_active",
    "Number of active StreamSignals connections",
)
