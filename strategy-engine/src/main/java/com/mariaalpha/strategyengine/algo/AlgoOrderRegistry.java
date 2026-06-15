package com.mariaalpha.strategyengine.algo;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

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

  public List<AlgoOrder> activeForSymbol(String symbol) {
    return byId.values().stream()
        .filter(a -> a.status() == AlgoOrder.Status.ACTIVE && a.symbol().equalsIgnoreCase(symbol))
        .toList();
  }

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
