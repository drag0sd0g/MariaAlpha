package com.mariaalpha.strategyengine.algo;

import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import com.mariaalpha.strategyengine.routing.SymbolStrategyRouter;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the submit / cancel side of the algo-execution REST surface (roadmap 3.4.4):
 *
 * <ol>
 *   <li>Validate that the requested strategy exists.
 *   <li>Apply the caller's parameters to the strategy (via {@link
 *       com.mariaalpha.strategyengine.strategy.TradingStrategy#updateParameters}).
 *   <li>Bind the strategy to the symbol so the tick-driven evaluation path picks it up.
 *   <li>Register the algo order in the in-memory {@link AlgoOrderRegistry}.
 *   <li>Fan out a {@code CREATED} (or {@code CANCELLED}) event to the {@code algo.progress}
 *       Kafka topic for WebSocket consumers (roadmap 3.4.5).
 * </ol>
 *
 * <p>This is a v1 surface — it deliberately doesn't yet track child-fill progress against the
 * algo's {@code targetQuantity}. That work needs {@code algoOrderId} propagation through
 * SignalPublisher → execution-engine → order-manager → orders.lifecycle, which is captured in
 * the docs as a future ticket.
 */
@Service
public class AlgoOrderService {

  private static final Logger LOG = LoggerFactory.getLogger(AlgoOrderService.class);

  private final StrategyRegistry strategyRegistry;
  private final SymbolStrategyRouter router;
  private final AlgoOrderRegistry orderRegistry;
  private final AlgoProgressPublisher progressPublisher;

  public AlgoOrderService(
      StrategyRegistry strategyRegistry,
      SymbolStrategyRouter router,
      AlgoOrderRegistry orderRegistry,
      AlgoProgressPublisher progressPublisher) {
    this.strategyRegistry = strategyRegistry;
    this.router = router;
    this.orderRegistry = orderRegistry;
    this.progressPublisher = progressPublisher;
  }

  /**
   * Submit a new algo order. Throws {@link IllegalArgumentException} if {@code strategyName} is
   * not registered — the controller maps this to a 400.
   */
  public AlgoOrder submit(AlgoOrderRequest request) {
    var strategyOpt = strategyRegistry.get(request.strategyName());
    if (strategyOpt.isEmpty()) {
      throw new IllegalArgumentException("Unknown strategy: " + request.strategyName());
    }
    var strategy = strategyOpt.get();

    var params = request.parameters() == null ? Map.<String, Object>of() : request.parameters();
    if (!params.isEmpty()) {
      strategy.updateParameters(params);
    }
    router.setActiveStrategy(request.symbol(), request.strategyName());

    var now = Instant.now();
    var order =
        new AlgoOrder(
            UUID.randomUUID(),
            request.symbol(),
            request.side(),
            request.targetQuantity(),
            request.strategyName(),
            params,
            AlgoOrder.Status.ACTIVE,
            now,
            now);
    orderRegistry.register(order);
    progressPublisher.publishLifecycle(order, AlgoProgressEvent.EventType.CREATED);
    LOG.info(
        "Algo order {} CREATED — {} {} qty={} via {}",
        order.algoOrderId(),
        order.side(),
        order.symbol(),
        order.targetQuantity(),
        order.strategyName());
    return order;
  }

  /**
   * Cancel an algo order. Unbinds the strategy from the symbol so no further signals fire and
   * emits the {@code CANCELLED} lifecycle event. Returns the updated record or empty if the id is
   * unknown / already terminal.
   */
  public Optional<AlgoOrder> cancel(UUID id) {
    var current = orderRegistry.find(id);
    if (current.isEmpty()) {
      return Optional.empty();
    }
    if (current.get().status() != AlgoOrder.Status.ACTIVE) {
      return current; // already terminal — caller can re-read for the final state.
    }
    var updated = orderRegistry.transition(id, AlgoOrder.Status.CANCELLED).orElseThrow();
    router.clearActiveStrategy(updated.symbol());
    progressPublisher.publishLifecycle(updated, AlgoProgressEvent.EventType.CANCELLED);
    LOG.info("Algo order {} CANCELLED", id);
    return Optional.of(updated);
  }
}
