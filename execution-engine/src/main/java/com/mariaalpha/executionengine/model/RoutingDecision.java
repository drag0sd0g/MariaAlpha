package com.mariaalpha.executionengine.model;

import java.time.Instant;

public record RoutingDecision(String orderId, String venue, String reason, Instant timestamp) {}
