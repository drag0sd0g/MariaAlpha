package com.mariaalpha.executionengine.router.scorer;

public interface VenueScoreCriterion {

  String name();

  double score(ScoringContext ctx);
}
