package com.mariaalpha.executionengine.router.scorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.router.Venue;
import com.mariaalpha.executionengine.router.VenueType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LatencyScorerTest {

  private final LatencyScorer scorer = new LatencyScorer();
  private final SorConfig config =
      new SorConfig(
          "scored",
          200,
          10,
          5,
          1000,
          new SorConfig.Weights(0.25, 0.25, 0.10, 0.15, 0.25),
          List.of());

  @Test
  void linearScale() {
    // latency 50, max 200 → 1 - 0.25 = 0.75
    assertThat(scorer.score(ctx(50))).isCloseTo(0.75, within(1e-9));
  }

  @Test
  void atOrAboveMaxIsZero() {
    assertThat(scorer.score(ctx(200))).isCloseTo(0.0, within(1e-9));
    assertThat(scorer.score(ctx(500))).isEqualTo(0.0);
  }

  @Test
  void zeroLatencyIsOne() {
    assertThat(scorer.score(ctx(0))).isCloseTo(1.0, within(1e-9));
  }

  private ScoringContext ctx(long latencyMs) {
    var order =
        new Order(
            new OrderSignal(
                "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "TEST", Instant.now()));
    var venue = new Venue("V", VenueType.LIT, latencyMs, 3, 2, 1.0, 10000, 0.95, "primary", true);
    return new ScoringContext(order, venue, null, config);
  }
}
