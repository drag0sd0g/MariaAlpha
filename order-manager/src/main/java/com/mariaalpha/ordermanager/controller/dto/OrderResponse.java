package com.mariaalpha.ordermanager.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mariaalpha.ordermanager.entity.OrderEntity;
import com.mariaalpha.ordermanager.model.OrderStatus;
import com.mariaalpha.ordermanager.model.OrderType;
import com.mariaalpha.ordermanager.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
    UUID orderId,
    String clientOrderId,
    String symbol,
    Side side,
    OrderType orderType,
    BigDecimal quantity,
    BigDecimal limitPrice,
    BigDecimal stopPrice,
    OrderStatus status,
    String strategy,
    BigDecimal filledQuantity,
    BigDecimal avgFillPrice,
    String exchangeOrderId,
    String venue,
    Instant createdAt,
    Instant updatedAt,
    List<FillResponse> fills) {

  public OrderResponse {
    fills = fills != null ? Collections.unmodifiableList(fills) : null;
  }

  public static OrderResponse of(OrderEntity order) {
    return new OrderResponse(
        order.getOrderId(),
        order.getClientOrderId(),
        order.getSymbol(),
        order.getSide(),
        order.getOrderType(),
        order.getQuantity(),
        order.getLimitPrice(),
        order.getStopPrice(),
        order.getStatus(),
        order.getStrategy(),
        order.getFilledQuantity(),
        order.getAvgFillPrice(),
        order.getExchangeOrderId(),
        order.getVenue(),
        order.getCreatedAt(),
        order.getUpdatedAt(),
        null);
  }

  public static OrderResponse of(OrderEntity order, List<FillResponse> fills) {
    return new OrderResponse(
        order.getOrderId(),
        order.getClientOrderId(),
        order.getSymbol(),
        order.getSide(),
        order.getOrderType(),
        order.getQuantity(),
        order.getLimitPrice(),
        order.getStopPrice(),
        order.getStatus(),
        order.getStrategy(),
        order.getFilledQuantity(),
        order.getAvgFillPrice(),
        order.getExchangeOrderId(),
        order.getVenue(),
        order.getCreatedAt(),
        order.getUpdatedAt(),
        fills);
  }
}
