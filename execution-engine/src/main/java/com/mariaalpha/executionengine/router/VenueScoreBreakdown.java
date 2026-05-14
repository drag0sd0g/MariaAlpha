package com.mariaalpha.executionengine.router;

import java.util.Map;

public record VenueScoreBreakdown(
    String venue, VenueType type, double weightedScore, Map<String, Double> criteria) {}
