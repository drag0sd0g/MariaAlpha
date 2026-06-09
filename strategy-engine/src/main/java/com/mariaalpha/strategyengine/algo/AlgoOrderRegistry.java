package com.mariaalpha.strategyengine.algo;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory store of active and recently-terminal algo orders (roadmap 3.4.4). A single instance is
 * sufficient because the strategy-engine itself is the source of truth for live algos — once
 * persistence is needed (e.g. crash recovery, multi-replica HA), this would move behind a repository
 * interface backed by Postgres alongside the orders table.
 *
 * <p>The registry deliberately retains terminal (cancelled / completed) algos so a downstream UI
 * can replay the final state via {@code GET /api/algo/orders/{id}} after the WebSocket event has
 * already fired. A future eviction policy can age them out — not needed at desk volumes.
 */
@Component
public class AlgoOrderRegistry {

  private final Map<UUID, AlgoOrder> byId = new ConcurrentHashMap<>();

  public AlgoOrder register(AlgoOrder order) {
    byId.put(order.algoOrderId(), order);
    return order;
  }

  public Optional<AlgoOrder> find(UUID id) {
    return Optional.ofNullable(byId.get(id));
  }

  public Collection<AlgoOrder> all() {
    return List.copyOf(byId.values());
  }

  /** Returns every algo order currently in {@link AlgoOrder.Status#ACTIVE} for a symbol. */
  public List<AlgoOrder> activeForSymbol(String symbol) {
    return byId.values().stream()
        .filter(a -> a.status() == AlgoOrder.Status.ACTIVE && a.symbol().equalsIgnoreCase(symbol))
        .toList();
  }

  /**
   * Transitions an algo order to a new status and stores it back. Returns the updated record or
   * empty if the id is unknown.
   */
  public Optional<AlgoOrder> transition(UUID id, AlgoOrder.Status newStatus) {
    var current = byId.get(id);
    if (current == null) {
      return Optional.empty();
    }
    var updated = current.withStatus(newStatus);
    byId.put(id, updated);
    return Optional.of(updated);
  }
}
