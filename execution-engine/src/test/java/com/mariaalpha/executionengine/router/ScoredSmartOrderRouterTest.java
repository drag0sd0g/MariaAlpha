package com.mariaalpha.executionengine.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.adapter.VenueAdapterRegistry;
import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.metrics.ExecutionMetrics;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.RoutingDecision;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.publisher.RoutingDecisionPublisher;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScoredSmartOrderRouterTest {

  private static final SorConfig.Weights WEIGHTS =
      new SorConfig.Weights(0.25, 0.25, 0.10, 0.15, 0.25);

  private RoutingDecisionPublisher publisher;
  private RoutingDecisionCache cache;
  private MarketStateTracker tracker;
  private ExecutionMetrics metrics;
  private VenueAdapterRegistry venueAdapters;
  private Set<String> healthyVenues;

  @BeforeEach
  void setUp() {
    publisher = mock(RoutingDecisionPublisher.class);
    cache = mock(RoutingDecisionCache.class);
    tracker = mock(MarketStateTracker.class);
    metrics = mock(ExecutionMetrics.class);
    venueAdapters = mock(VenueAdapterRegistry.class);
    healthyVenues = new HashSet<>();
    when(venueAdapters.healthyNames()).thenAnswer(inv -> new HashSet<>(healthyVenues));
  }

  @Test
  void singleVenuePicksItself() {
    var lit = venue("PRIMARY", VenueType.LIT, true);
    var router = router(List.of(lit), stubScorer(Map.of("PRIMARY", 0.7)));
    when(tracker.getMarketState("AAPL")).thenReturn(market());

    var order = order();
    var decision = router.route(order);

    assertThat(decision.venue()).isEqualTo("PRIMARY");
    assertThat(order.getVenue()).isEqualTo("PRIMARY");
    assertThat(decision.candidateScores()).hasSize(1);
    assertThat(decision.weights()).hasSize(5);
  }

  @Test
  void picksHighestScoreAcrossVenues() {
    var lit = venue("PRIMARY", VenueType.LIT, true);
    var dark = venue("DARK_POOL_A", VenueType.DARK, true);
    var router = router(List.of(lit, dark), stubScorer(Map.of("PRIMARY", 0.4, "DARK_POOL_A", 0.8)));
    when(tracker.getMarketState("AAPL")).thenReturn(market());

    var decision = router.route(order());

    assertThat(decision.venue()).isEqualTo("DARK_POOL_A");
    assertThat(decision.selectedVenueType()).isEqualTo(VenueType.DARK);
    assertThat(decision.selectedScore()).isEqualTo(0.8);
  }

  @Test
  void tieBreakUsesRegistryOrder() {
    var lit = venue("PRIMARY", VenueType.LIT, true);
    var dark = venue("DARK_POOL_A", VenueType.DARK, true);
    var router = router(List.of(lit, dark), stubScorer(Map.of("PRIMARY", 0.5, "DARK_POOL_A", 0.5)));
    when(tracker.getMarketState("AAPL")).thenReturn(market());

    var decision = router.route(order());

    assertThat(decision.venue()).isEqualTo("PRIMARY");
  }

  @Test
  void publishesAndCachesDecision() {
    var router =
        router(List.of(venue("PRIMARY", VenueType.LIT, true)), stubScorer(Map.of("PRIMARY", 0.7)));
    when(tracker.getMarketState(anyString())).thenReturn(market());

    router.route(order());

    verify(publisher).publish(any(RoutingDecision.class));
    verify(cache).put(any(RoutingDecision.class));
  }

  @Test
  void recordsMetrics() {
    var router =
        router(List.of(venue("PRIMARY", VenueType.LIT, true)), stubScorer(Map.of("PRIMARY", 0.7)));
    when(tracker.getMarketState(anyString())).thenReturn(market());

    router.route(order());

    verify(metrics).recordSorRouting("PRIMARY", "LIT");
    verify(metrics).recordSorCandidateScore(anyString(), anyString(), anyDouble());
    verify(metrics).recordSorScoringDuration(anyLong());
  }

  @Test
  void noEnabledVenuesReturnsDegradedDecision() {
    var disabled = venue("PRIMARY", VenueType.LIT, false);
    var router = router(List.of(disabled), stubScorer(Map.of()));

    var decision = router.route(order());

    assertThat(decision.venue()).isEqualTo("PRIMARY");
    assertThat(decision.reason()).contains("No enabled");
    assertThat(decision.selectedVenueType()).isNull();
    assertThat(decision.candidateScores()).isEmpty();
    verify(metrics, never()).recordSorRouting(anyString(), anyString());
  }

  @Test
  void noVenuesAtAllUsesUnknown() {
    var router = router(List.of(), stubScorer(Map.of()));

    var decision = router.route(order());

    assertThat(decision.venue()).isEqualTo("UNKNOWN");
  }

  @Test
  void excludesUnhealthyVenuesFromScoring() {
    var lit = venue("PRIMARY", VenueType.LIT, true);
    var dark = venue("DARK_POOL_A", VenueType.DARK, true);
    var scorer = stubScorer(Map.of("PRIMARY", 0.4, "DARK_POOL_A", 0.8));
    var router = router(List.of(lit, dark), scorer);
    healthyVenues.remove("DARK_POOL_A");
    when(tracker.getMarketState(anyString())).thenReturn(market());

    var decision = router.route(order());

    assertThat(decision.venue()).isEqualTo("PRIMARY");
    assertThat(decision.candidateScores()).hasSize(1);
    assertThat(decision.candidateScores().get(0).venue()).isEqualTo("PRIMARY");
  }

  @Test
  void evaluatesEveryEnabledVenue() {
    var lit = venue("PRIMARY", VenueType.LIT, true);
    var dark = venue("DARK_POOL_A", VenueType.DARK, true);
    var internal = venue("INTERNAL_CROSS", VenueType.INTERNAL, true);
    var scorer = mock(VenueScorer.class);
    when(scorer.score(any(), any(), any()))
        .thenAnswer(
            inv -> {
              Venue v = inv.getArgument(1);
              double s =
                  switch (v.name()) {
                    case "PRIMARY" -> 0.4;
                    case "DARK_POOL_A" -> 0.8;
                    case "INTERNAL_CROSS" -> 0.6;
                    default -> 0.0;
                  };
              return new VenueScoreBreakdown(v.name(), v.type(), s, Map.of());
            });
    var router = router(List.of(lit, dark, internal), scorer);
    when(tracker.getMarketState(anyString())).thenReturn(market());

    var decision = router.route(order());

    assertThat(decision.candidateScores()).hasSize(3);
    assertThat(decision.venue()).isEqualTo("DARK_POOL_A");
    verify(scorer, times(3)).score(any(), any(), any());
  }

  private ScoredSmartOrderRouter router(List<Venue> venues, VenueScorer scorer) {
    var config = new SorConfig("scored", 200, 10, 5, 1000, WEIGHTS, venues);
    var registry = new VenueRegistry(config);
    healthyVenues.clear();
    venues.forEach(v -> healthyVenues.add(v.name()));
    return new ScoredSmartOrderRouter(
        registry, scorer, publisher, cache, tracker, metrics, config, venueAdapters);
  }

  private static VenueScorer stubScorer(Map<String, Double> venueToScore) {
    var scorer = mock(VenueScorer.class);
    when(scorer.score(any(), any(), any()))
        .thenAnswer(
            inv -> {
              Venue v = inv.getArgument(1);
              double s = venueToScore.getOrDefault(v.name(), 0.0);
              return new VenueScoreBreakdown(v.name(), v.type(), s, Map.of());
            });
    return scorer;
  }

  private static Venue venue(String name, VenueType type, boolean enabled) {
    return new Venue(
        name, type, 50, 3, 2, type == VenueType.LIT ? 1.0 : 0.2, 10000, 0.95, "primary", enabled);
  }

  private static Order order() {
    return new Order(
        new OrderSignal("AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "T", Instant.now()));
  }

  private static MarketState market() {
    return new MarketState(
        "AAPL",
        new BigDecimal("178.50"),
        new BigDecimal("178.54"),
        new BigDecimal("178.52"),
        Instant.now());
  }
}
