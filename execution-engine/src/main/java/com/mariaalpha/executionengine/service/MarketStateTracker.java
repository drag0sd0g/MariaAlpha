package com.mariaalpha.executionengine.service;

import com.mariaalpha.executionengine.model.MarketState;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class MarketStateTracker {

  private final ConcurrentHashMap<String, MarketState> states = new ConcurrentHashMap<>();

  public void update(MarketState state) {
    states.put(state.symbol(), state);
  }

  public MarketState getMarketState(String symbol) {
    return states.get(symbol);
  }
}
