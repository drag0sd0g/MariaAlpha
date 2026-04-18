package com.mariaalpha.executionengine.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Fill(
    String fillId,
    String orderId,
    BigDecimal fillPrice,
    int fillQuantity,
    String venue,
    Instant filledAt) {}
