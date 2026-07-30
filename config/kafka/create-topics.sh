#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVER:-kafka:9092}"
KAFKA_BIN="/opt/kafka/bin"

create_topic() {
  local topic="$1"
  local partitions="$2"
  local retention_ms="$3"

  echo "Creating topic: ${topic} (partitions=${partitions}, retention=${retention_ms}ms)"
  "${KAFKA_BIN}/kafka-topics.sh" \
    --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --create \
    --if-not-exists \
    --topic "${topic}" \
    --partitions "${partitions}" \
    --config retention.ms="${retention_ms}"
}

create_compacted_topic() {
  local topic="$1"
  local partitions="$2"

  echo "Creating compacted topic: ${topic} (partitions=${partitions})"
  "${KAFKA_BIN}/kafka-topics.sh" \
    --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --create \
    --if-not-exists \
    --topic "${topic}" \
    --partitions "${partitions}" \
    --config cleanup.policy=compact \
    --config min.cleanable.dirty.ratio=0.1 \
    --config segment.ms=600000
}

create_topic "market-data.ticks"     1 14400000    # 4 hours
create_topic "strategy.signals"      1 259200000   # 3 days
create_topic "orders.lifecycle"      1 259200000   # 3 days
create_topic "positions.updates"     1 259200000   # 3 days
create_topic "analytics.tca"         1 259200000   # 3 days
create_topic "analytics.risk-alerts" 1 259200000   # 3 days
create_topic "routing.decisions"     1 259200000   # 3 days
create_topic "orders.dlq"            1 2592000000  # 30 days
# Roadmap 3.4.5 — algo-execution lifecycle + signal events for WebSocket consumers.
create_topic "algo.progress"         1 259200000   # 3 days

# Roadmap 4.6.1 — covariance/correlation risk model. Compacted rather than time-retained: only
# the latest model matters, and a restarting execution-engine reading from `earliest` gets it
# immediately instead of running on the conservative sum-of-absolutes fallback until the next
# publish interval elapses.
create_compacted_topic "analytics.risk-model" 1

echo "All topics created successfully."
