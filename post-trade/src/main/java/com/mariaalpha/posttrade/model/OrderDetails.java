package com.mariaalpha.posttrade.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderDetails(
    UUID orderId,
    String clientOrderId,
    String symbol,
    Side side,
    OrderType orderType,
    BigDecimal quantity,
    BigDecimal limitPrice,
    OrderStatus status,
    String strategy,
    BigDecimal filledQuantity,
    BigDecimal avgFillPrice,
    String exchangeOrderId,
    String venue,
    Instant createdAt,
    Instant updatedAt,
    List<FillRecord> fills) {

  public List<FillRecord> safeFills() {
    return fills == null ? List.of() : fills;
  }
}
