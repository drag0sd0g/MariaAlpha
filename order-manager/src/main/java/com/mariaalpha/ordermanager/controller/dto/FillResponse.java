package com.mariaalpha.ordermanager.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mariaalpha.ordermanager.entity.FillEntity;
import com.mariaalpha.ordermanager.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FillResponse(
    UUID fillId,
    UUID orderId,
    String exchangeFillId,
    String symbol,
    Side side,
    BigDecimal fillPrice,
    BigDecimal fillQuantity,
    BigDecimal commission,
    String venue,
    Instant filledAt) {

  public static FillResponse of(FillEntity fill) {
    return new FillResponse(
        fill.getFillId(),
        fill.getOrder() != null ? fill.getOrder().getOrderId() : null,
        fill.getExchangeFillId(),
        fill.getSymbol(),
        fill.getSide(),
        fill.getFillPrice(),
        fill.getFillQuantity(),
        fill.getCommission(),
        fill.getVenue(),
        fill.getFilledAt());
  }
}
