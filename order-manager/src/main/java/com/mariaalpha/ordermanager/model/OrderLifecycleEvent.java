package com.mariaalpha.ordermanager.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderLifecycleEvent(
    String orderId,
    OrderStatus status,
    OrderSnapshotEvent order,
    FillEvent fill,
    String reason,
    Instant timestamp) {}
