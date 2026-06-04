package com.mariaalpha.executionengine.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Projection of the order-manager's PositionSnapshot that the execution-engine deserializes from
 * the Redis cache. Mirrors the order-manager's wire format — extra fields are tolerated for forward
 * compatibility.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PositionSnapshot(
    String symbol,
    BigDecimal netQuantity,
    BigDecimal avgEntryPrice,
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl,
    BigDecimal lastMarkPrice,
    Instant timestamp) {

  /**
   * Notional exposure in dollars: {@code netQuantity × markPrice}, falling back to {@code
   * netQuantity × avgEntryPrice} when no mark price has been captured yet. Returns ZERO when both
   * prices are absent.
   */
  public BigDecimal notional() {
    if (netQuantity == null) {
      return BigDecimal.ZERO;
    }
    var price = lastMarkPrice != null ? lastMarkPrice : avgEntryPrice;
    if (price == null) {
      return BigDecimal.ZERO;
    }
    return netQuantity.multiply(price);
  }
}
