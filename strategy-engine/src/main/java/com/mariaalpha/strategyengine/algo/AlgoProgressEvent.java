package com.mariaalpha.strategyengine.algo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlgoProgressEvent(
    UUID algoOrderId,
    EventType eventType,
    String symbol,
    Side parentSide,
    long targetQuantity,
    String strategyName,
    AlgoOrder.Status status,
    Side signalSide,
    Long signalQuantity,
    BigDecimal signalLimitPrice,
    Instant timestamp) {

  public enum EventType {
    CREATED,
    CANCELLED,
    SIGNAL_EMITTED,
    COMPLETED
  }
}
