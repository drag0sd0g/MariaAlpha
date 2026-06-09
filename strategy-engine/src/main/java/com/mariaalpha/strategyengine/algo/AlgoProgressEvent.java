package com.mariaalpha.strategyengine.algo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted on the {@code algo.progress} topic each time an {@link AlgoOrder}'s lifecycle
 * advances or a strategy signal fires for its symbol. Subscribers (api-gateway WebSocket, future UI
 * tape) consume this to render real-time parent-order progress.
 *
 * <p>{@code eventType} carries the transition that caused the emission ({@code CREATED} when the
 * algo order is submitted, {@code CANCELLED} on DELETE, {@code SIGNAL_EMITTED} when the bound
 * strategy publishes a signal). The signal-level fields ({@code signalSide}, {@code signalQuantity},
 * {@code signalLimitPrice}) are populated only on {@code SIGNAL_EMITTED}; null otherwise.
 */
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
