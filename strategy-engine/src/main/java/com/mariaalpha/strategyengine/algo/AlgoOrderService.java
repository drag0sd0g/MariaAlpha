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

  public AlgoOrder submit(AlgoOrderRequest request) {
    var strategyOpt = strategyRegistry.get(request.strategyName());
    if (strategyOpt.isEmpty()) {
      throw new IllegalArgumentException("Unknown strategy: " + request.strategyName());
    }
    var strategy = strategyOpt.get();

    var params = request.parameters() == null ? Map.<String, Object>of() : request.parameters();
    var effectiveParams = new java.util.HashMap<String, Object>(params);
    effectiveParams.putIfAbsent("side", request.side().name());
    effectiveParams.putIfAbsent("targetQuantity", request.targetQuantity());
    strategy.updateParameters(effectiveParams);
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

  public Optional<AlgoOrder> cancel(UUID id) {
    var current = orderRegistry.find(id);
    if (current.isEmpty()) {
      return Optional.empty();
    }
    if (current.get().status() != AlgoOrder.Status.ACTIVE) {
      return current;
    }
    var updated = orderRegistry.transition(id, AlgoOrder.Status.CANCELLED).orElseThrow();
    router.clearActiveStrategy(updated.symbol());
    progressPublisher.publishLifecycle(updated, AlgoProgressEvent.EventType.CANCELLED);
    LOG.info("Algo order {} CANCELLED", id);
    return Optional.of(updated);
  }
}
