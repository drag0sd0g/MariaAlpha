package com.mariaalpha.ordermanager.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
