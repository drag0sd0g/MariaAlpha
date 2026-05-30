"""Orders consumer — feeds axe matching when desk orders enter SUBMITTED state."""

from __future__ import annotations

import json
from typing import TYPE_CHECKING

import structlog
from confluent_kafka import Consumer, KafkaError

from analytics.axes.matcher import IncomingLeg
from analytics.metrics import AXES_MATCHES

if TYPE_CHECKING:
    from analytics.axes.matcher import AxeMatcher
    from analytics.config import Settings

logger = structlog.get_logger()


class OrdersConsumer:
    """Polls ``orders.lifecycle`` and asks the matcher for axe suggestions on each SUBMITTED."""

    def __init__(self, settings: Settings, matcher: AxeMatcher) -> None:
        self._consumer = Consumer(
            {
                "bootstrap.servers": settings.kafka_bootstrap_servers,
                "group.id": f"{settings.kafka_consumer_group}-orders",
                "auto.offset.reset": "latest",
                "enable.auto.commit": True,
            }
        )
        self._topic = settings.kafka_orders_lifecycle_topic
        self._matcher = matcher
        self._running = True
        # Last-seen match suggestions for a given order_id, surfaced via REST.
        self._last_matches: dict[str, list[dict[str, object]]] = {}

    def run(self) -> None:
        self._consumer.subscribe([self._topic])
        logger.info("orders_consumer_started", topic=self._topic)
        while self._running:
            msg = self._consumer.poll(timeout=1.0)
            if msg is None:
                continue
            err = msg.error()
            if err is not None:
                if err.code() == KafkaError._PARTITION_EOF:
                    continue
                logger.error("orders_consumer_error", error=str(err))
                continue
            try:
                value = msg.value()
                if value is None:
                    continue
                event = json.loads(value.decode("utf-8"))
                self._handle(event)
            except Exception:
                logger.exception("orders_payload_processing_failed")

    def _handle(self, event: dict[str, object]) -> None:
        if event.get("status") != "SUBMITTED":
            return
        order = event.get("order") or {}
        if not isinstance(order, dict):
            return
        symbol = order.get("symbol")
        side = order.get("side")
        quantity = order.get("quantity")
        order_id = event.get("orderId")
        if not (
            isinstance(symbol, str)
            and side in ("BUY", "SELL")
            and isinstance(quantity, int)
            and isinstance(order_id, str)
        ):
            return
        suggestions = self._matcher.match(
            IncomingLeg(order_id=order_id, symbol=symbol, side=side, quantity=quantity)
        )
        if not suggestions:
            return
        rows = [_suggestion_dict(s) for s in suggestions]
        self._last_matches[order_id] = rows
        for s in suggestions:
            quality = (
                "HIGH" if s.confidence >= 0.66 else "MEDIUM" if s.confidence >= 0.33 else "LOW"
            )
            AXES_MATCHES.labels(symbol=s.symbol, match_quality=quality).inc()

    def last_matches(self, order_id: str) -> list[dict[str, object]] | None:
        return self._last_matches.get(order_id)

    def stop(self) -> None:
        self._running = False
        self._consumer.close()


def _suggestion_dict(s) -> dict[str, object]:
    return {
        "axeId": s.axe_id,
        "clientId": s.client_id,
        "symbol": s.symbol,
        "axeSide": s.axe_side,
        "matchedQuantity": s.matched_quantity,
        "axeRemainingBefore": s.axe_remaining_before,
        "confidence": s.confidence,
        "score": s.score,
        "limitPrice": s.limit_price,
    }
