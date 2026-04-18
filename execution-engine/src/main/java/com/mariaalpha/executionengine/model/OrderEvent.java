package com.mariaalpha.executionengine.model;

import java.time.Instant;

public record OrderEvent(
    String orderId,
    OrderStatus status,
    OrderSnapshot order,
    Fill fill,
    String reason,
    Instant timestamp) {}
