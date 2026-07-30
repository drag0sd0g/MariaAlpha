"""Publisher for ``analytics.risk-model`` — the covariance model execution-engine consumes.

The pre-trade VaR gate lives in execution-engine (Java) because it sits on the order hot path; the
estimator lives here because this is where numpy is. This topic is the seam between them.

**Volatilities plus a correlation matrix, not a raw covariance.** Three reasons: the payload is
readable in ``kafka-console-consumer``; the two halves carry independent trust, so the Java side
can substitute a configured volatility for a thinly-traded name while keeping the estimated
correlation structure; and a unit-diagonal correlation matrix is trivially validatable, which
makes a malformed model easy to reject rather than easy to miss.

**Compacted topic, single key.** Only the latest model matters, and a restarting execution-engine
reading from ``earliest`` on a compacted log gets it immediately instead of waiting up to a full
publish interval with no model — during which its VaR check would fall back to the conservative
sum-of-absolutes aggregation.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Any

import structlog
from confluent_kafka import Producer

from analytics.metrics import RISK_MODEL_PUBLISHED

if TYPE_CHECKING:
    from analytics.config import Settings
    from analytics.portfolio.covariance import CovarianceEstimate

logger = structlog.get_logger()

MODEL_KEY = "GLOBAL"


def build_payload(estimate: CovarianceEstimate, estimator: str) -> dict[str, Any]:
    """Serialise an estimate into the wire contract consumed by execution-engine."""
    generated_at = datetime.now(UTC)
    return {
        "modelId": f"cov-{generated_at.strftime('%Y%m%dT%H%M%SZ')}",
        "generatedAt": generated_at.isoformat().replace("+00:00", "Z"),
        "estimator": estimator,
        "source": estimate.source,
        "barSeconds": estimate.bar_seconds,
        "observations": estimate.observations,
        "shrinkageIntensity": round(estimate.shrinkage_intensity, 8),
        "tradingDaysPerYear": estimate.trading_days_per_year,
        "symbols": list(estimate.symbols),
        "annualizedVolatility": [round(float(v), 8) for v in estimate.volatilities],
        "correlation": [[round(float(c), 8) for c in row] for row in estimate.correlation],
        "psdRepaired": estimate.psd_repaired,
        "conditionNumber": round(estimate.condition_number, 6),
    }


class RiskModelPublisher:
    """Best-effort fire-and-forget publisher, mirroring ``RiskAlertPublisher``."""

    def __init__(self, settings: Settings) -> None:
        self._producer = Producer(
            {
                "bootstrap.servers": settings.kafka_bootstrap_servers,
                "linger.ms": 5,
                "compression.type": "snappy",
            }
        )
        self._topic = settings.kafka_risk_model_topic
        self._estimator = f"{settings.covariance_estimator}+ledoit_wolf"

    def publish(self, estimate: CovarianceEstimate) -> dict[str, Any] | None:
        """Publish ``estimate``. Returns the payload, or ``None`` when nothing was sent."""
        if not estimate.symbols:
            logger.warning("risk_model_publish_skipped", reason="no symbols")
            return None
        payload = build_payload(estimate, self._estimator)
        try:
            self._producer.produce(
                self._topic,
                key=MODEL_KEY.encode("utf-8"),
                value=json.dumps(payload).encode("utf-8"),
            )
            self._producer.poll(0)
            RISK_MODEL_PUBLISHED.inc()
            logger.info(
                "risk_model_published",
                topic=self._topic,
                model_id=payload["modelId"],
                symbols=len(estimate.symbols),
                source=estimate.source,
                observations=estimate.observations,
            )
            return payload
        except Exception:
            logger.exception("risk_model_publish_failed", topic=self._topic)
            return None

    def close(self) -> None:
        try:
            self._producer.flush(2.0)
        except Exception:
            logger.exception("risk_model_publisher_close_failed")
