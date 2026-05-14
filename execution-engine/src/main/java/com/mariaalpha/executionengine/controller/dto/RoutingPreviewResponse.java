package com.mariaalpha.executionengine.controller.dto;

import com.mariaalpha.executionengine.router.VenueScoreBreakdown;
import java.util.List;

public record RoutingPreviewResponse(
    String suggestedVenue, List<VenueScoreBreakdown> candidateScores, String message) {}
