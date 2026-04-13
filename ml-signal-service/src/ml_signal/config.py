"""Application configuration via environment variables."""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Settings loaded from environment variables prefixed with ML_SIGNAL_."""

    # Kafka
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_ticks_topic: str = "market-data.ticks"
    kafka_consumer_group: str = "ml-signal-service"

    # gRPC
    grpc_port: int = 50051
    grpc_max_workers: int = 10

    # FastAPI
    api_port: int = 8090

    # Model
    signal_model_path: str = "ml-models/signal_model.joblib"

    # Feature engine
    bar_interval_seconds: int = 60
    min_bars_for_features: int = 50
    max_bars_retained: int = 200

    model_config = {"env_prefix": "ML_SIGNAL_"}
