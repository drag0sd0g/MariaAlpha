package com.mariaalpha.strategyengine.algo;

import com.mariaalpha.strategyengine.model.Side;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AlgoOrderResponse(
    UUID algoOrderId,
    String symbol,
    Side side,
    long targetQuantity,
    String strategyName,
    Map<String, Object> parameters,
    AlgoOrder.Status status,
    Instant createdAt,
    Instant updatedAt) {

  public static AlgoOrderResponse from(AlgoOrder order) {
    return new AlgoOrderResponse(
        order.algoOrderId(),
        order.symbol(),
        order.side(),
        order.targetQuantity(),
        order.strategyName(),
        order.parameters(),
        order.status(),
        order.createdAt(),
        order.updatedAt());
  }
}
