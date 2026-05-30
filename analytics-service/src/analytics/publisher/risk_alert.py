"""Publisher for ``analytics.risk-alerts`` — used by the toxicity detector."""

from __future__ import annotations

import json
from typing import TYPE_CHECKING

import structlog
from confluent_kafka import Producer

if TYPE_CHECKING:
    from analytics.config import Settings

logger = structlog.get_logger()


class RiskAlertPublisher:
    """Best-effort fire-and-forget publisher."""

    def __init__(self, settings: Settings) -> None:
        self._producer = Producer(
            {
                "bootstrap.servers": settings.kafka_bootstrap_servers,
                "linger.ms": 5,
                "compression.type": "snappy",
            }
        )
        self._topic = settings.kafka_risk_alerts_topic

    def publish(self, alert: dict[str, object]) -> None:
        key = str(alert.get("strategy") or alert.get("symbol") or "")
        try:
            payload = json.dumps(alert).encode("utf-8")
            self._producer.produce(self._topic, key=key.encode("utf-8"), value=payload)
            self._producer.poll(0)
        except Exception:
            logger.exception("risk_alert_publish_failed", topic=self._topic, key=key)

    def close(self) -> None:
        try:
            self._producer.flush(2.0)
        except Exception:
            logger.exception("risk_alert_publisher_close_failed")
