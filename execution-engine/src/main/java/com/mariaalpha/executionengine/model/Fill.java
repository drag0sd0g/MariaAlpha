package com.mariaalpha.executionengine.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Fill(
    String fillId,
    String orderId,
    String symbol,
    Side side,
    BigDecimal fillPrice,
    int fillQuantity,
    String exchangeFillId,
    String venue,
    Instant filledAt) {}
