package com.mariaalpha.posttrade.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderLifecycleEvent(
    String orderId,
    OrderStatus status,
    OrderSnapshotEvent order,
    FillEvent fill,
    String reason,
    Instant timestamp) {}
