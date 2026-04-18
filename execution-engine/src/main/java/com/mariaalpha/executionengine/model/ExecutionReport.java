package com.mariaalpha.executionengine.model;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutionReport(
    String exchangeOrderId,
    BigDecimal fillPrice,
    int fillQuantity,
    int remainingQuantity,
    String venue,
    Instant timestamp) {}
