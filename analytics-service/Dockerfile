FROM python:3.12-slim

WORKDIR /app

# librdkafka system dep for confluent-kafka
RUN apt-get update && apt-get install -y --no-install-recommends librdkafka1 \
    && rm -rf /var/lib/apt/lists/*

COPY analytics-service/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY analytics-service/src/ /app/src/

ENV PYTHONPATH="/app/src"

EXPOSE 8095

HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=5 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8095/health')" || exit 1

CMD ["python", "-m", "analytics"]
