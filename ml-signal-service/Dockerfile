FROM python:3.12-slim AS base

WORKDIR /app

# LightGBM requires libgomp (OpenMP runtime), not included in python:slim
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*

# Install production dependencies
COPY ml-signal-service/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

# Copy proto stubs (must be pre-generated: just proto)
COPY proto/generated/python/ /app/proto/

# Copy source code
COPY ml-signal-service/src/ /app/src/

# Copy pre-trained model (may not exist — service degrades gracefully)
COPY ml-models/ /app/ml-models/

ENV PYTHONPATH="/app/src:/app/proto"
ENV ML_SIGNAL_SIGNAL_MODEL_PATH="/app/ml-models/signal_model.joblib"

EXPOSE 8090 50051

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8090/health')" || exit 1

CMD ["python", "-m", "ml_signal"]