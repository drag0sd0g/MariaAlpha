package com.mariaalpha.strategyengine.strategy;

import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import java.util.Map;
import java.util.Optional;

public interface TradingStrategy {
  String name();

  void onTick(MarketTick tick);

  Optional<OrderSignal> evaluate(String symbol);

  Map<String, Object> getParameters();

  void updateParameters(Map<String, Object> params);
}
