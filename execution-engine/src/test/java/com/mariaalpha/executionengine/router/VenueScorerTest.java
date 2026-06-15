package com.mariaalpha.executionengine.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.router.scorer.VenueScoreCriterion;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class VenueScorerTest {

  @Test
  void weightedSumMatchesWeights() {
    var weights = new SorConfig.Weights(0.20, 0.20, 0.20, 0.20, 0.20);
    var config = new SorConfig("scored", 200, 10, 5, 1000, weights, List.of());
    var c1 = stub("PriceImprovement", 0.5);
    var c2 = stub("Liquidity", 1.0);
    var c3 = stub("Latency", 0.0);
    var c4 = stub("Fees", 0.5);
    var c5 = stub("InformationLeakage", 1.0);
    var scorer = new VenueScorer(List.of(c1, c2, c3, c4, c5), config);

    var b = scorer.score(order(), venue(), null);
    assertThat(b.weightedScore()).isCloseTo(0.6, within(1e-9));
    assertThat(b.criteria()).containsEntry("PriceImprovement", 0.5);
    assertThat(b.criteria()).containsEntry("Liquidity", 1.0);
  }

  @Test
  void unknownCriterionGetsZeroWeight() {
    var weights = new SorConfig.Weights(1.0, 0.0, 0.0, 0.0, 0.0);
    var config = new SorConfig("scored", 200, 10, 5, 1000, weights, List.of());
    var rogue = stub("RogueCriterion", 1.0);
    var pi = stub("PriceImprovement", 0.5);
    var scorer = new VenueScorer(List.of(rogue, pi), config);
    var b = scorer.score(order(), venue(), null);
    assertThat(b.weightedScore()).isCloseTo(0.5, within(1e-9));
  }

  @Test
  void breakdownContainsVenueIdentity() {
    var weights = new SorConfig.Weights(0.25, 0.25, 0.10, 0.15, 0.25);
    var config = new SorConfig("scored", 200, 10, 5, 1000, weights, List.of());
    var scorer = new VenueScorer(List.of(stub("PriceImprovement", 0.5)), config);
    var v = venue();
    var b = scorer.score(order(), v, null);
    assertThat(b.venue()).isEqualTo(v.name());
    assertThat(b.type()).isEqualTo(v.type());
  }

  private static VenueScoreCriterion stub(String name, double score) {
    var c = mock(VenueScoreCriterion.class);
    when(c.name()).thenReturn(name);
    when(c.score(org.mockito.ArgumentMatchers.any())).thenReturn(score);
    return c;
  }

  private static Order order() {
    return new Order(
        new OrderSignal("AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "T", Instant.now()));
  }

  private static Venue venue() {
    return new Venue("V", VenueType.LIT, 50, 3, 2, 1.0, 10000, 0.95, "primary", true);
  }
}
