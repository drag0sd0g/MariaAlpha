package com.mariaalpha.executionengine.service;

import java.math.BigDecimal;
import java.util.Map;
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

  public BigDecimal getTotalGrossExposure() {
    return positions.values().stream()
        .map(BigDecimal::abs)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public Map<String, BigDecimal> snapshot() {
    return Map.copyOf(positions);
  }
}
