package com.mariaalpha.executionengine.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.model.RoutingDecision;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingDecisionCacheTest {

  @Test
  void putGetRoundtrip() {
    var cache = cacheOfSize(10);
    var d = decision("o-1");
    cache.put(d);
    assertThat(cache.get("o-1")).contains(d);
  }

  @Test
  void boundedAtConfiguredSize() {
    var cache = cacheOfSize(2);
    cache.put(decision("o-1"));
    cache.put(decision("o-2"));
    cache.put(decision("o-3"));
    assertThat(cache.size()).isEqualTo(2);
  }

  @Test
  void evictsLeastRecentlyUsed() {
    var cache = cacheOfSize(2);
    cache.put(decision("o-1"));
    cache.put(decision("o-2"));
    assertThat(cache.get("o-1")).isPresent();
    cache.put(decision("o-3"));
    assertThat(cache.get("o-1")).isPresent();
    assertThat(cache.get("o-2")).isEmpty();
    assertThat(cache.get("o-3")).isPresent();
  }

  private static RoutingDecisionCache cacheOfSize(int n) {
    var weights = new SorConfig.Weights(0.25, 0.25, 0.10, 0.15, 0.25);
    return new RoutingDecisionCache(new SorConfig("scored", 200, 10, 5, n, weights, List.of()));
  }

  private static RoutingDecision decision(String orderId) {
    return RoutingDecision.legacy(orderId, "PRIMARY", "test", Instant.now());
  }
}
