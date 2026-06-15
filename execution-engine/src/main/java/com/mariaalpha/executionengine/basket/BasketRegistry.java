package com.mariaalpha.executionengine.basket;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class BasketRegistry {

  private final Map<String, BasketState> baskets = new ConcurrentHashMap<>();
  private final Map<String, String> legToBasket = new ConcurrentHashMap<>();

  public void register(BasketState state) {
    baskets.put(state.basketId(), state);
  }

  public void linkLeg(String legOrderId, String basketId) {
    legToBasket.put(legOrderId, basketId);
  }

  public Optional<BasketState> find(String basketId) {
    return Optional.ofNullable(baskets.get(basketId));
  }

  public Optional<BasketView> view(String basketId) {
    return find(basketId).map(BasketState::toView);
  }

  public List<BasketView> all() {
    return baskets.values().stream().map(BasketState::toView).toList();
  }

  public Optional<String> recordLegFill(String legOrderId, int fillQuantity, boolean complete) {
    var basketId = legToBasket.get(legOrderId);
    if (basketId == null) {
      return Optional.empty();
    }
    var state = baskets.get(basketId);
    if (state == null || !state.recordFill(legOrderId, fillQuantity, complete)) {
      return Optional.empty();
    }
    if (complete) {
      legToBasket.remove(legOrderId);
    }
    return Optional.of(basketId);
  }

  public int trackedBaskets() {
    return baskets.size();
  }
}
