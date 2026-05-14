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

class InformationLeakageScorerTest {

  private final InformationLeakageScorer scorer = new InformationLeakageScorer();
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
  void litFullLeakage() {
    assertThat(scorer.score(ctx(1.0))).isCloseTo(0.0, within(1e-9));
  }

  @Test
  void darkPartial() {
    assertThat(scorer.score(ctx(0.2))).isCloseTo(0.8, within(1e-9));
  }

  @Test
  void internalZero() {
    assertThat(scorer.score(ctx(0.0))).isCloseTo(1.0, within(1e-9));
  }

  private ScoringContext ctx(double leakage) {
    var order =
        new Order(
            new OrderSignal(
                "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "TEST", Instant.now()));
    var venue = new Venue("V", VenueType.LIT, 50, 3, 2, leakage, 10000, 0.95, "primary", true);
    return new ScoringContext(order, venue, null, config);
  }
}
