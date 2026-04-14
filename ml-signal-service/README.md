# ML Signal Service

Python microservice that consumes market data ticks from Kafka, computes technical indicator features, and serves directional predictions (LONG / SHORT / NEUTRAL) via gRPC. The strategy engine calls this service before acting on each order signal — if the ML model contradicts the signal with high confidence, the signal is suppressed.

See [ml-signal-service-explainer.md](../docs/ml-signal-service-explainer.md) for an in-depth technical walkthrough.

## Interfaces

| Interface | Port | Protocol | Purpose |
|-----------|------|----------|---------|
| gRPC | 50051 | HTTP/2 + Protobuf | `GetSignal`, `GetRegime`, `StreamSignals` |
| FastAPI | 8090 | HTTP/1.1 + JSON | `/health`, `/ready`, `/metrics`, `/v1/models/reload` |

## Prerequisites

- Python 3.12 (via [mise](https://mise.jdx.dev/) or system)
- Docker running (for Kafka)
- Alpaca API credentials (for training only)

## Setup

```bash
# From the repo root
python3 -m venv .venv
source .venv/bin/activate
pip install -r ml-signal-service/requirements.txt
pip install -r ml-signal-service/requirements-dev.txt

# Generate proto stubs (requires grpcio-tools)
just proto
```

## Running Locally

```bash
# Start infrastructure (Kafka, etc.)
just run

# Run the service
cd ml-signal-service
python -m ml_signal
```

The service starts and serves gRPC even without a trained model — it returns NEUTRAL with confidence 0 until a model is loaded.

## Configuration

All settings are loaded from environment variables prefixed with `ML_SIGNAL_`:

| Variable | Default | Description |
|----------|---------|-------------|
| `ML_SIGNAL_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker address |
| `ML_SIGNAL_KAFKA_TICKS_TOPIC` | `market-data.ticks` | Kafka topic for market data ticks |
| `ML_SIGNAL_KAFKA_CONSUMER_GROUP` | `ml-signal-service` | Kafka consumer group ID |
| `ML_SIGNAL_GRPC_PORT` | `50051` | gRPC server port |
| `ML_SIGNAL_GRPC_MAX_WORKERS` | `10` | gRPC thread pool size |
| `ML_SIGNAL_API_PORT` | `8090` | FastAPI HTTP port |
| `ML_SIGNAL_SIGNAL_MODEL_PATH` | `ml-models/signal_model.joblib` | Path to the trained model artifact |
| `ML_SIGNAL_BAR_INTERVAL_SECONDS` | `60` | OHLCV bar aggregation interval |
| `ML_SIGNAL_MIN_BARS_FOR_FEATURES` | `50` | Minimum bars before computing features |
| `ML_SIGNAL_MAX_BARS_RETAINED` | `200` | Maximum bars kept in memory per symbol |

## Training the Model

The training script downloads historical 1-minute bars from Alpaca, computes the 15 technical indicator features, labels each bar with the sign of the 5-bar forward return, and trains a LightGBM binary classifier.

### Prerequisites

- `ALPACA_API_KEY_ID` and `ALPACA_API_SECRET_KEY` environment variables set (paper or live account)

### Running

```bash
source .env   # or export Alpaca credentials manually
cd ml-signal-service
python scripts/train_signal_model.py
```

### What the Script Does

1. **Fetches data** — Downloads 6 months of 1-minute bars for AAPL, MSFT, GOOGL, AMZN, META from the Alpaca Market Data API (IEX feed)
2. **Computes features** — Calculates the same 15 indicators used at inference time: EMA(20), EMA(50), ema_cross, ema_20_dist, ema_50_dist, RSI(14), MACD line/signal/histogram, ATR(14), atr_norm, volume_ratio, realized_vol, return_1, return_5
3. **Labels** — Binary classification: `1` if `close[i + 5] > close[i]` (price goes up in 5 minutes), `0` otherwise
4. **Splits** — Time-ordered 80/20 train/validation split (no random shuffling — avoids look-ahead bias)
5. **Trains** — LightGBM with 200 trees, learning rate 0.05, feature/bagging regularization
6. **Evaluates** — Prints accuracy, classification report, and feature importance
7. **Saves** — Writes model artifact to `ml-models/signal_model.joblib`

### Model Artifact

The saved `.joblib` file contains:

```python
{
    "model": LGBMClassifier,      # the trained 200-tree ensemble
    "version": "20260413-214607", # timestamp of training run
    "feature_names": [...],       # ordered list of 15 feature names
    "accuracy": 0.543,            # validation accuracy
    "n_train": 450000,            # training samples
    "n_val": 112000,              # validation samples
    "symbols": ["AAPL", ...],    # symbols used for training
    "label_horizon": 5,           # prediction horizon in bars
}
```

### Retraining

To retrain with updated data, simply rerun the script. To deploy the new model without restarting the service:

```bash
curl -X POST http://localhost:8090/v1/models/reload
```

### Expected Output

```
Training signal model on 5 symbols, 180 days of 1-min bars
Label horizon: 5 bars (minutes)

  Fetching AAPL bars from 2025-10-16 to 2026-04-14...
  AAPL: fetched 46800 bars
  ...

Total samples after cleanup: 217000
Label distribution:
1    112000
0    105000

Train: 173600 samples, Val: 43400 samples

Training LightGBM...

Validation accuracy: 0.5430
              precision    recall  f1-score   support

   DOWN/FLAT       0.53      0.51      0.52     21200
          UP       0.55      0.57      0.56     22200

Feature importance (gain):
  rsi_14                3245.1
  ema_cross             2891.3
  return_1              2567.8
  ...

Model saved to ml-models/signal_model.joblib
Version: 20260413-214607
```

## Linting and Type Checking

```bash
cd ml-signal-service
ruff check src/ tests/ scripts/
mypy src/
```

## Testing

```bash
cd ml-signal-service
pytest tests/ -v
```

All 40 tests cover: indicators (numerical correctness), feature engine (tick aggregation, bar building, feature computation), signal model (inference, thresholds, hot-reload), gRPC servicer (GetSignal, GetRegime, StreamSignals), and FastAPI endpoints (health, ready, metrics, reload).

## Docker

The service is included in the root `docker-compose.yml`:

```bash
docker compose up -d ml-signal-service
```

The Dockerfile uses `python:3.12-slim` with `libgomp1` installed (required by LightGBM for OpenMP). Proto stubs must be pre-generated (`just proto`) before building.
