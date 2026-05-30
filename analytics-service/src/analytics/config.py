"""Application configuration via environment variables."""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Loaded from environment variables prefixed with ``ANALYTICS_``."""

    # Kafka
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_tca_topic: str = "analytics.tca"
    kafka_market_data_topic: str = "market-data.ticks"
    kafka_orders_lifecycle_topic: str = "orders.lifecycle"
    kafka_risk_alerts_topic: str = "analytics.risk-alerts"
    kafka_consumer_group: str = "analytics-service"

    # FastAPI
    api_port: int = 8095

    # Toxicity detector — markout horizons in seconds and the toxicity threshold
    # (negative markout above this magnitude in bps trips a risk alert).
    toxicity_horizons_seconds: tuple[int, ...] = (60, 300, 1800)
    toxicity_threshold_bps: float = 5.0
    toxicity_min_observations: int = 10

    # PnL attribution — commission / hedging assumption (bps of notional).
    commission_bps: float = 0.5

    # Axe matching — TTL for axes before they age out.
    axe_default_ttl_minutes: int = 60
    axe_match_min_quantity: int = 100

    model_config = {"env_prefix": "ANALYTICS_"}
