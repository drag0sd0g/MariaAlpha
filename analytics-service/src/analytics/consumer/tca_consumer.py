"""TCA consumer — feeds both the PnL attribution engine and the toxicity detector."""

from __future__ import annotations

import json
from datetime import datetime
from typing import TYPE_CHECKING, cast

import structlog
from confluent_kafka import Consumer, KafkaError

from analytics.metrics import PNL_ATTRIBUTION_USD
from analytics.pnl.attribution import TcaInput
from analytics.toxicity.detector import FillRecord

if TYPE_CHECKING:
    from analytics.config import Settings
    from analytics.pnl.attribution import PnlAttributionEngine
    from analytics.toxicity.detector import FlowToxicityDetector

logger = structlog.get_logger()


class TcaConsumer:
    """Polls ``analytics.tca`` and routes each row to attribution + toxicity."""

    def __init__(
        self,
        settings: Settings,
        attribution: PnlAttributionEngine,
        toxicity: FlowToxicityDetector,
    ) -> None:
        self._consumer = Consumer(
            {
                "bootstrap.servers": settings.kafka_bootstrap_servers,
                "group.id": f"{settings.kafka_consumer_group}-tca",
                "auto.offset.reset": "earliest",
                "enable.auto.commit": True,
            }
        )
        self._topic = settings.kafka_tca_topic
        self._attribution = attribution
        self._toxicity = toxicity
        self._running = True

    def run(self) -> None:
        self._consumer.subscribe([self._topic])
        logger.info("tca_consumer_started", topic=self._topic)
        while self._running:
            msg = self._consumer.poll(timeout=1.0)
            if msg is None:
                continue
            err = msg.error()
            if err is not None:
                if err.code() == KafkaError._PARTITION_EOF:
                    continue
                logger.error("tca_consumer_error", error=str(err))
                continue
            try:
                value = msg.value()
                if value is None:
                    continue
                payload = json.loads(value.decode("utf-8"))
                self._handle(payload)
            except Exception:
                logger.exception("tca_payload_processing_failed")

    def _handle(self, payload: dict[str, object]) -> None:
        try:
            tca = _to_tca_input(payload)
        except (KeyError, TypeError, ValueError) as e:
            logger.warning("tca_payload_skipped", reason=str(e))
            return
        attribution = self._attribution.attribute(tca)
        for component, value in (
            ("spread", attribution.spread_usd),
            ("timing", attribution.timing_usd),
            ("market", attribution.market_usd),
            ("commission", attribution.commission_usd),
            ("residual", attribution.residual_usd),
        ):
            PNL_ATTRIBUTION_USD.labels(strategy=tca.strategy, component=component).observe(value)
        fill = FillRecord(
            order_id=tca.order_id,
            strategy=tca.strategy,
            symbol=tca.symbol,
            side=tca.side,
            fill_price=tca.realized_avg_price,
            fill_ts_seconds=tca.computed_at.timestamp(),
        )
        self._toxicity.on_fill(fill)

    def stop(self) -> None:
        self._running = False
        self._consumer.close()


def _to_tca_input(payload: dict[str, object]) -> TcaInput:
    side_raw = payload.get("side")
    if side_raw not in ("BUY", "SELL"):
        raise ValueError(f"unexpected side: {side_raw!r}")
    side = cast(str, side_raw)
    computed_at_raw = payload.get("computedAt")
    if not isinstance(computed_at_raw, str):
        raise ValueError("computedAt missing or non-string")
    computed_at = _parse_iso(computed_at_raw)
    commission_total = payload.get("commissionTotal")
    return TcaInput(
        order_id=str(payload["orderId"]),
        strategy=str(payload.get("strategy") or "UNKNOWN"),
        symbol=str(payload["symbol"]),
        side=side,
        quantity=float(cast(float, payload["quantity"])),
        arrival_price=float(cast(float, payload["arrivalPrice"])),
        realized_avg_price=float(cast(float, payload["realizedAvgPrice"])),
        vwap_benchmark_price=float(
            cast(float, payload.get("vwapBenchmarkPrice", payload["arrivalPrice"]))
        ),
        spread_cost_bps=float(cast(float, payload.get("spreadCostBps", 0) or 0)),
        commission_total=(
            float(cast(float, commission_total)) if commission_total is not None else None
        ),
        computed_at=computed_at,
    )


def _parse_iso(value: str) -> datetime:
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    return datetime.fromisoformat(value)
