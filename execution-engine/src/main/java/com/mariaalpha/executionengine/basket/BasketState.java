package com.mariaalpha.executionengine.basket;

import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.Side;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BasketState {

  private final String basketId;
  private final String name;
  private final Instant createdAt;
  private final Map<String, BasketLeg> legs = new LinkedHashMap<>();

  public BasketState(String basketId, String name, Instant createdAt) {
    this.basketId = basketId;
    this.name = name;
    this.createdAt = createdAt;
  }

  public String basketId() {
    return basketId;
  }

  public synchronized void addLeg(String legOrderId, String symbol, Side side, int targetQuantity) {
    legs.put(legOrderId, new BasketLeg(legOrderId, symbol, side, targetQuantity));
  }

  public synchronized void recordSubmissionOutcome(
      String legOrderId, OrderStatus status, String reason) {
    var leg = legs.get(legOrderId);
    if (leg != null) {
      leg.applySubmissionOutcome(status, reason);
    }
  }

  public synchronized boolean recordFill(String legOrderId, int fillQuantity, boolean complete) {
    var leg = legs.get(legOrderId);
    if (leg == null) {
      return false;
    }
    leg.applyFill(fillQuantity, complete);
    return true;
  }

  public synchronized boolean isFilled() {
    return toView().status() == BasketStatus.FILLED;
  }

  public synchronized BasketView toView() {
    var legViews = legs.values().stream().map(BasketLeg::toView).toList();
    int accepted = 0;
    int rejected = 0;
    int filledLegs = 0;
    int targetQuantity = 0;
    int filledQuantity = 0;
    for (var leg : legs.values()) {
      if (leg.accepted()) {
        accepted++;
        targetQuantity += leg.toView().targetQuantity();
      } else {
        rejected++;
      }
      if (leg.fullyFilled()) {
        filledLegs++;
      }
      filledQuantity += leg.filledQuantity();
    }
    return new BasketView(
        basketId,
        name,
        createdAt,
        deriveStatus(accepted, filledLegs),
        legs.size(),
        accepted,
        rejected,
        filledLegs,
        targetQuantity,
        filledQuantity,
        legViews);
  }

  private BasketStatus deriveStatus(int acceptedLegs, int filledLegs) {
    if (acceptedLegs == 0) {
      return BasketStatus.REJECTED;
    }
    if (filledLegs == acceptedLegs) {
      return BasketStatus.FILLED;
    }
    return filledLegs > 0 || hasPartialFill()
        ? BasketStatus.PARTIALLY_FILLED
        : BasketStatus.SUBMITTED;
  }

  private boolean hasPartialFill() {
    return legs.values().stream().anyMatch(l -> l.accepted() && l.filledQuantity() > 0);
  }
}
