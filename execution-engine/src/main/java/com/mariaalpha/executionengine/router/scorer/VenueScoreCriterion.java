package com.mariaalpha.executionengine.router.scorer;

public interface VenueScoreCriterion {

  /** Stable identifier used as a key in the audit JSON breakdown and weights map. */
  String name();

  /** Return a score in {@code [0.0, 1.0]}. Implementations must clamp. */
  double score(ScoringContext ctx);
}
