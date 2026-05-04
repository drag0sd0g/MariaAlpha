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

create_topic "market-data.ticks"     1 14400000    # 4 hours
create_topic "strategy.signals"      1 259200000   # 3 days
create_topic "orders.lifecycle"      1 259200000   # 3 days
create_topic "positions.updates"     1 259200000   # 3 days
create_topic "analytics.tca"         1 259200000   # 3 days
create_topic "analytics.risk-alerts" 1 259200000   # 3 days
create_topic "routing.decisions"     1 259200000   # 3 days
create_topic "orders.dlq"            1 2592000000  # 30 days

echo "All topics created successfully."
