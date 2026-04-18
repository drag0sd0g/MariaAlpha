package com.mariaalpha.executionengine.service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class PositionTracker {

  private final ConcurrentHashMap<String, BigDecimal> positions = new ConcurrentHashMap<>();

  public void updatePosition(String symbol, BigDecimal notional) {
    positions.put(symbol, notional);
  }

  public BigDecimal getPositionNotional(String symbol) {
    return positions.getOrDefault(symbol, BigDecimal.ZERO);
  }

  /**
   * Sums the absolute value of every position (long positions are positive, shorts are negative) to
   * get total market exposure regardless of direction.
   */
  public BigDecimal getTotalGrossExposure() {
    return positions.values().stream()
        .map(BigDecimal::abs)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
