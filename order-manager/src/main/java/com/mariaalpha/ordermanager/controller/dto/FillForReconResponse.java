package com.mariaalpha.ordermanager.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mariaalpha.ordermanager.entity.FillEntity;
import com.mariaalpha.ordermanager.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Recon-tailored fill projection — fill rows plus the parent order's client/exchange ids, which the
 * post-trade reconciliation engine needs to match against external venue activity. Mirrors {@link
 * com.mariaalpha.posttrade.model.FillForReconRecord} on the consumer side.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FillForReconResponse(
    UUID fillId,
    UUID orderId,
    String clientOrderId,
    String exchangeOrderId,
    String symbol,
    Side side,
    BigDecimal fillPrice,
    BigDecimal fillQuantity,
    BigDecimal commission,
    String venue,
    String exchangeFillId,
    Instant filledAt) {

  public static FillForReconResponse of(FillEntity fill) {
    var order = fill.getOrder();
    return new FillForReconResponse(
        fill.getFillId(),
        order == null ? null : order.getOrderId(),
        order == null ? null : order.getClientOrderId(),
        order == null ? null : order.getExchangeOrderId(),
        fill.getSymbol(),
        fill.getSide(),
        fill.getFillPrice(),
        fill.getFillQuantity(),
        fill.getCommission(),
        fill.getVenue(),
        fill.getExchangeFillId(),
        fill.getFilledAt());
  }
}
