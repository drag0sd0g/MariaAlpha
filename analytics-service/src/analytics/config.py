"""Application configuration via environment variables."""

from typing import Literal

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Loaded from environment variables prefixed with ``ANALYTICS_``."""

    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_tca_topic: str = "analytics.tca"
    kafka_market_data_topic: str = "market-data.ticks"
    kafka_orders_lifecycle_topic: str = "orders.lifecycle"
    kafka_risk_alerts_topic: str = "analytics.risk-alerts"
    kafka_consumer_group: str = "analytics-service"

    api_port: int = 8095

    toxicity_horizons_seconds: tuple[int, ...] = (60, 300, 1800)
    toxicity_threshold_bps: float = 5.0
    toxicity_min_observations: int = 10

    commission_bps: float = 0.5

    axe_default_ttl_minutes: int = 60
    axe_match_min_quantity: int = 100

    # --- Roadmap 4.6.1 — portfolio construction & risk ---

    kafka_positions_topic: str = "positions.updates"
    kafka_risk_model_topic: str = "analytics.risk-model"

    portfolio_reference_path: str = "/app/config/portfolio.yml"
    stress_scenarios_path: str = "/app/config/stress-scenarios.yml"
    # The system has no cash ledger, so NAV = |marked positions| + this notional base.
    portfolio_base_nav: float = 1_000_000.0

    returns_bar_seconds: int = 60
    # ``arrival`` buckets ticks by wall-clock ingest time — correct for the looping simulated
    # tape, whose CSV timestamps repeat on every pass. ``event`` uses the tick's own timestamp,
    # which is correct for a live Alpaca feed.
    returns_clock: Literal["arrival", "event"] = "arrival"
    returns_min_observations: int = 30
    returns_max_bars: int = 1_440

    covariance_estimator: Literal["sample", "ewma"] = "ewma"
    covariance_ewma_lambda: float = 0.97
    covariance_shrinkage_floor: float = 0.20
    trading_days_per_year: float = 252.0
    # 252 trading days x 6.5 hours x 3600 seconds
    trading_seconds_per_year: float = 5_896_800.0

    risk_confidence_level: float = 0.95
    risk_horizon_days: float = 1.0
    risk_mc_simulations: int = 10_000
    risk_mc_distribution: Literal["normal", "t"] = "normal"
    risk_mc_df: float = 5.0
    risk_mc_seed: int = 20260729
    # Mirrors execution-engine.risk.max-intraday-var so both services alert at the same level.
    risk_var_limit_usd: float = 750_000.0
    stress_loss_limit_usd: float = 1_500_000.0

    optimizer_risk_aversion: float = 3.0
    optimizer_max_weight: float = 0.35
    bl_tau: float = 0.05
    bl_market_risk_aversion: float = 2.5

    rebalance_min_trade_notional: float = 2_500.0
    rebalance_no_trade_band_bps: float = 25.0
    rebalance_lot_size: int = 1
    rebalance_impact_eta: float = 1.0
    rebalance_submit_enabled: bool = False
    execution_engine_url: str = "http://execution-engine:8084"

    risk_model_publish_seconds: int = 300
    risk_model_enabled: bool = True

    model_config = {"env_prefix": "ANALYTICS_"}
