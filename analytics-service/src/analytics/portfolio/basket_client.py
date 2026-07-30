"""Optional submission of a rebalance plan to execution-engine's basket API.

Sending real orders from an analytics service is a foot-gun, so this is off unless
``ANALYTICS_REBALANCE_SUBMIT_ENABLED=true``, it lives behind a *different* endpoint from the one
that computes the plan, and the full payload is logged at INFO before anything goes out.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

import httpx
import structlog

if TYPE_CHECKING:
    from analytics.config import Settings

logger = structlog.get_logger()

BASKET_PATH = "/api/execution/baskets"


class BasketSubmissionError(RuntimeError):
    """Raised when execution-engine refuses or cannot be reached."""

    def __init__(self, message: str, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


class BasketClient:
    """Thin POST client for ``/api/execution/baskets``."""

    def __init__(self, settings: Settings, timeout_seconds: float = 10.0) -> None:
        self._base_url = settings.execution_engine_url.rstrip("/")
        self._enabled = settings.rebalance_submit_enabled
        self._timeout = timeout_seconds

    @property
    def enabled(self) -> bool:
        return self._enabled

    def submit(self, basket_request: dict[str, Any]) -> dict[str, Any]:
        if not self._enabled:
            raise BasketSubmissionError(
                "basket submission is disabled; set ANALYTICS_REBALANCE_SUBMIT_ENABLED=true "
                "to allow the analytics service to send live orders"
            )
        legs = basket_request.get("legs") or []
        if not legs:
            raise BasketSubmissionError("refusing to submit a basket with no legs")

        url = f"{self._base_url}{BASKET_PATH}"
        logger.info(
            "submitting_rebalance_basket",
            url=url,
            name=basket_request.get("name"),
            legs=legs,
        )
        try:
            response = httpx.post(url, json=basket_request, timeout=self._timeout)
        except httpx.HTTPError as exc:
            raise BasketSubmissionError(f"execution-engine unreachable: {exc}") from exc

        if response.status_code >= 400:
            raise BasketSubmissionError(
                f"execution-engine rejected the basket: {response.text}",
                status_code=response.status_code,
            )
        try:
            body = response.json()
        except ValueError:
            body = {"raw": response.text}
        logger.info("rebalance_basket_submitted", status=response.status_code, response=body)
        return {"status": response.status_code, "basket": body}
