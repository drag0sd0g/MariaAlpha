package com.mariaalpha.strategyengine.algo;

import com.mariaalpha.strategyengine.model.Side;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory record of an algorithmic parent order submitted through the REST surface (roadmap
 * 3.4.4). Each algo order binds the named strategy to the requested symbol and applies the caller's
 * parameters. Lifecycle transitions are emitted to the {@code algo.progress} Kafka topic for
 * downstream WebSocket consumers (roadmap 3.4.5).
 *
 * <p>Filled-quantity / progress tracking is intentionally out of scope here — it requires
 * propagating {@code algoOrderId} through the signal → execution → order-manager chain. Captured in
 * the docs as a future-work item.
 */
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
