package com.mariaalpha.executionengine.router;

import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.model.RoutingDecision;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class RoutingDecisionCache {

  private final Map<String, RoutingDecision> cache;

  public RoutingDecisionCache(SorConfig config) {
    var max = Math.max(1, config.decisionCacheSize());
    this.cache =
        Collections.synchronizedMap(
            new LinkedHashMap<String, RoutingDecision>(max, 0.75f, true) {
              @Override
              protected boolean removeEldestEntry(Map.Entry<String, RoutingDecision> eldest) {
                return super.size() > max;
              }
            });
  }

  public void put(RoutingDecision decision) {
    cache.put(decision.orderId(), decision);
  }

  public Optional<RoutingDecision> get(String orderId) {
    return Optional.ofNullable(cache.get(orderId));
  }

  public int size() {
    return cache.size();
  }
}
