package com.mariaalpha.executionengine.router;

import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.router.scorer.ScoringContext;
import com.mariaalpha.executionengine.router.scorer.VenueScoreCriterion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class VenueScorer {

  private final List<VenueScoreCriterion> criteria;
  private final SorConfig config;

  public VenueScorer(List<VenueScoreCriterion> criteria, SorConfig config) {
    this.criteria = List.copyOf(criteria);
    this.config = config;
  }

  public VenueScoreBreakdown score(Order order, Venue venue, MarketState marketState) {
    var ctx = new ScoringContext(order, venue, marketState, config);
    var perCriterion = new LinkedHashMap<String, Double>();
    var weights = config.weights().asMap();
    var weighted = 0.0;
    for (var criterion : criteria) {
      var criterionScore = criterion.score(ctx);
      perCriterion.put(criterion.name(), criterionScore);
      var criterionWeight = weights.getOrDefault(criterion.name(), 0.0);
      weighted += criterionWeight * criterionScore;
    }
    return new VenueScoreBreakdown(venue.name(), venue.type(), weighted, Map.copyOf(perCriterion));
  }
}
