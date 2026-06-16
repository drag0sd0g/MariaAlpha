package com.mariaalpha.strategyengine.algo;

import com.mariaalpha.strategyengine.model.Side;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AlgoOrder(
    UUID algoOrderId,
    String symbol,
    Side side,
    long targetQuantity,
    String strategyName,
    Map<String, Object> parameters,
    Status status,
    Instant createdAt,
    Instant updatedAt) {

  public enum Status {
    ACTIVE,
    CANCELLED,
    COMPLETED
  }

  public AlgoOrder withStatus(Status newStatus) {
    return new AlgoOrder(
        algoOrderId,
        symbol,
        side,
        targetQuantity,
        strategyName,
        parameters,
        newStatus,
        createdAt,
        Instant.now());
  }
}
