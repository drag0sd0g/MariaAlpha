"""Analytics Service entry point.

Wires up the three analytics components, the Kafka consumers that feed them, and the FastAPI
HTTP surface. Mirrors the layout of ``ml-signal-service.__main__``:

- FastAPI runs on the main thread (uvicorn blocks).
- Each Kafka consumer runs in its own daemon thread.
- A background ``threading.Timer`` ticks the toxicity detector so horizon-based markouts get
  computed even without traffic flow.
"""

from __future__ import annotations

import threading
import time

import structlog
import uvicorn

from analytics.api.app import create_app
from analytics.axes.matcher import AxeMatcher
from analytics.config import Settings
from analytics.consumer.market_data import MarketDataCache, MarketDataConsumer
from analytics.consumer.orders_consumer import OrdersConsumer
from analytics.consumer.tca_consumer import TcaConsumer
from analytics.pnl.attribution import PnlAttributionEngine
from analytics.publisher.risk_alert import RiskAlertPublisher
from analytics.toxicity.detector import FlowToxicityDetector

logger = structlog.get_logger()


def main() -> None:
    settings = Settings()
    logger.info(
        "starting_analytics_service",
        api_port=settings.api_port,
        kafka=settings.kafka_bootstrap_servers,
        toxicity_horizons=settings.toxicity_horizons_seconds,
    )

    market_cache = MarketDataCache()
    risk_publisher = RiskAlertPublisher(settings)
    toxicity = FlowToxicityDetector(
        horizons_seconds=settings.toxicity_horizons_seconds,
        threshold_bps=settings.toxicity_threshold_bps,
        min_observations=settings.toxicity_min_observations,
        price_lookup=market_cache.price_at,
        alert_publisher=risk_publisher.publish,
        clock=time.time,
    )
    attribution = PnlAttributionEngine(commission_bps=settings.commission_bps)
    matcher = AxeMatcher(
        default_ttl_seconds=settings.axe_default_ttl_minutes * 60,
        min_match_quantity=settings.axe_match_min_quantity,
    )

    market_consumer = MarketDataConsumer(settings, market_cache)
    threading.Thread(target=market_consumer.run, name="market-data-consumer", daemon=True).start()

    tca_consumer = TcaConsumer(settings, attribution, toxicity)
    threading.Thread(target=tca_consumer.run, name="tca-consumer", daemon=True).start()

    orders_consumer = OrdersConsumer(settings, matcher)
    threading.Thread(target=orders_consumer.run, name="orders-consumer", daemon=True).start()

    # Periodic tick of the toxicity detector so horizon-based markouts are evaluated.
    def _toxicity_loop() -> None:
        while True:
            try:
                toxicity.tick()
            except Exception:
                logger.exception("toxicity_tick_failed")
            time.sleep(5)

    threading.Thread(target=_toxicity_loop, name="toxicity-tick", daemon=True).start()

    app = create_app(
        settings=settings,
        toxicity=toxicity,
        attribution=attribution,
        matcher=matcher,
        market_cache=market_cache,
        orders_consumer=orders_consumer,
    )
    uvicorn.run(app, host="0.0.0.0", port=settings.api_port, log_level="info")


if __name__ == "__main__":
    main()
