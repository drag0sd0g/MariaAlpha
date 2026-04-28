package com.mariaalpha.posttrade.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FillEvent(
    String fillId,
    String orderId,
    String exchangeFillId,
    String symbol,
    Side side,
    BigDecimal fillPrice,
    int fillQuantity,
    BigDecimal commission,
    String venue,
    Instant filledAt) {}
