package com.mariaalpha.executionengine.router;

import com.mariaalpha.executionengine.adapter.VenueAdapterRegistry;
import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.metrics.ExecutionMetrics;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RoutingDecision;
import com.mariaalpha.executionengine.publisher.RoutingDecisionPublisher;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "execution-engine.sor.mode",
    havingValue = "scored",
    matchIfMissing = true)
public class ScoredSmartOrderRouter implements SmartOrderRouter {

  private static final Logger LOG = LoggerFactory.getLogger(ScoredSmartOrderRouter.class);

  private final VenueRegistry registry;
  private final VenueScorer scorer;
  private final RoutingDecisionPublisher publisher;
  private final RoutingDecisionCache cache;
  private final MarketStateTracker marketStateTracker;
  private final ExecutionMetrics metrics;
  private final SorConfig config;
  private final VenueAdapterRegistry venueAdapters;

  public ScoredSmartOrderRouter(
      VenueRegistry registry,
      VenueScorer scorer,
      RoutingDecisionPublisher publisher,
      RoutingDecisionCache cache,
      MarketStateTracker marketStateTracker,
      ExecutionMetrics metrics,
      SorConfig config,
      VenueAdapterRegistry venueAdapters) {
    this.registry = registry;
    this.scorer = scorer;
    this.publisher = publisher;
    this.cache = cache;
    this.marketStateTracker = marketStateTracker;
    this.metrics = metrics;
    this.config = config;
    this.venueAdapters = venueAdapters;
  }

  @Override
  public RoutingDecision route(Order order) {
    long start = System.nanoTime();
    var market = marketStateTracker.getMarketState(order.getSymbol());
    var healthy = venueAdapters.healthyNames();
    var routable = registry.enabled().stream().filter(v -> healthy.contains(v.name())).toList();

    if (routable.isEmpty()) {
      var fallback = registry.all().stream().findFirst().map(Venue::name).orElse("UNKNOWN");
      var decision =
          new RoutingDecision(
              order.getOrderId(),
              fallback,
              "No enabled+healthy venues; falling back to first registered",
              Instant.now(),
              order.getSymbol(),
              order.getSide(),
              order.getQuantity(),
              order.getOrderType(),
              null,
              null,
              List.of(),
              config.weights().asMap(),
              snapshot(market));
      order.setVenue(fallback);
      finalize(decision);
      return decision;
    }

    var breakdowns = routable.stream().map(v -> scorer.score(order, v, market)).toList();
    breakdowns.forEach(
        b -> metrics.recordSorCandidateScore(b.venue(), b.type().name(), b.weightedScore()));

    var chosen =
        breakdowns.stream()
            .max(Comparator.comparingDouble(VenueScoreBreakdown::weightedScore))
            .orElseThrow();

    order.setVenue(chosen.venue());

    var decision =
        new RoutingDecision(
            order.getOrderId(),
            chosen.venue(),
            String.format(
                "Selected %s (%s) with score %.4f; %d candidate venues evaluated",
                chosen.venue(), chosen.type(), chosen.weightedScore(), breakdowns.size()),
            Instant.now(),
            order.getSymbol(),
            order.getSide(),
            order.getQuantity(),
            order.getOrderType(),
            chosen.type(),
            chosen.weightedScore(),
            breakdowns,
            config.weights().asMap(),
            snapshot(market));

    finalize(decision);
    metrics.recordSorScoringDuration(System.nanoTime() - start);
    return decision;
  }

  private void finalize(RoutingDecision decision) {
    publisher.publish(decision);
    cache.put(decision);
    if (decision.selectedVenueType() != null) {
      metrics.recordSorRouting(decision.venue(), decision.selectedVenueType().name());
    }
    LOG.info(
        "SOR routed order {} to {} ({})",
        decision.orderId(),
        decision.venue(),
        decision.selectedVenueType());
  }

  private RoutingDecision.MarketSnapshot snapshot(MarketState m) {
    if (m == null || m.bidPrice() == null || m.askPrice() == null) {
      return null;
    }
    var bid = m.bidPrice();
    var ask = m.askPrice();
    if (bid.signum() <= 0 || ask.signum() <= 0 || ask.compareTo(bid) <= 0) {
      return new RoutingDecision.MarketSnapshot(bid, ask, null, null);
    }
    var mid = bid.add(ask).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    var spreadBps =
        ask.subtract(bid).divide(mid, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(10_000));
    return new RoutingDecision.MarketSnapshot(bid, ask, mid, spreadBps);
  }
}
