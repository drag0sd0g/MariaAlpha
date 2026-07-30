"""Positions consumer — keeps ``PortfolioState`` current from ``positions.updates``."""

from __future__ import annotations

import json
from typing import TYPE_CHECKING

import structlog
from confluent_kafka import Consumer, KafkaError

if TYPE_CHECKING:
    from analytics.config import Settings
    from analytics.portfolio.state import PortfolioState

logger = structlog.get_logger()


class PositionsConsumer:
    """Polls ``positions.updates`` and applies each snapshot to the portfolio state.

    ``auto.offset.reset=earliest`` matters here: the topic is the only source of the book, and a
    restarting analytics service that started at ``latest`` would report an empty portfolio until
    the next fill. Replaying from the beginning rebuilds it, and because each payload is a full
    per-symbol snapshot rather than a delta, replay is idempotent.
    """

    def __init__(self, settings: Settings, state: PortfolioState) -> None:
        self._consumer = Consumer(
            {
                "bootstrap.servers": settings.kafka_bootstrap_servers,
                "group.id": f"{settings.kafka_consumer_group}-positions",
                "auto.offset.reset": "earliest",
                "enable.auto.commit": True,
            }
        )
        self._topic = settings.kafka_positions_topic
        self._state = state
        self._running = True

    def run(self) -> None:
        self._consumer.subscribe([self._topic])
        logger.info("positions_consumer_started", topic=self._topic)
        while self._running:
            msg = self._consumer.poll(timeout=1.0)
            if msg is None:
                continue
            err = msg.error()
            if err is not None:
                if err.code() == KafkaError._PARTITION_EOF:
                    continue
                logger.error("positions_consumer_error", error=str(err))
                continue
            try:
                value = msg.value()
                if value is None:
                    continue
                payload = json.loads(value.decode("utf-8"))
                if not isinstance(payload, dict):
                    continue
                if not self._state.apply(payload):
                    logger.warning("position_snapshot_skipped", payload=payload)
            except Exception:
                logger.exception("position_payload_processing_failed")

    def stop(self) -> None:
        self._running = False
        self._consumer.close()
