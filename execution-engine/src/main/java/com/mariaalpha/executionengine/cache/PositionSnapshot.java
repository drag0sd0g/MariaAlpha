package com.mariaalpha.executionengine.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PositionSnapshot(
    String symbol,
    BigDecimal netQuantity,
    BigDecimal avgEntryPrice,
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl,
    BigDecimal lastMarkPrice,
    Instant timestamp) {

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
