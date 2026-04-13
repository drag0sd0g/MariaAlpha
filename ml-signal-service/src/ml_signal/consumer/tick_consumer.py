"""Kafka consumer for market-data.ticks."""

from __future__ import annotations

import json
from typing import TYPE_CHECKING

import structlog
from confluent_kafka import Consumer, KafkaError

if TYPE_CHECKING:
    from ml_signal.config import Settings
    from ml_signal.features.engine import FeatureEngine

logger = structlog.get_logger()


class TickConsumer:
    """Polls Kafka for market ticks and feeds them to the FeatureEngine."""

    def __init__(self, settings: Settings, feature_engine: FeatureEngine) -> None:
        self._consumer = Consumer(
            {
                "bootstrap.servers": settings.kafka_bootstrap_servers,
                "group.id": settings.kafka_consumer_group,
                "auto.offset.reset": "latest",
                "enable.auto.commit": True,
            }
        )
        self._topic = settings.kafka_ticks_topic
        self._feature_engine = feature_engine
        self._running = True

    def run(self) -> None:
        """Blocking poll loop — call from a background thread."""
        self._consumer.subscribe([self._topic])
        logger.info("tick_consumer_started", topic=self._topic)

        while self._running:
            msg = self._consumer.poll(timeout=1.0)
            if msg is None:
                continue

            err = msg.error()
            if err is not None:
                if err.code() == KafkaError._PARTITION_EOF:
                    continue
                logger.error("kafka_consumer_error", error=str(err))
                continue

            try:
                value = msg.value()
                if value is None:
                    continue
                tick = json.loads(value.decode("utf-8"))
                self._feature_engine.on_tick(tick)
            except Exception:
                logger.exception("tick_processing_failed")

    def stop(self) -> None:
        """Signal the consumer to stop."""
        self._running = False
        self._consumer.close()
