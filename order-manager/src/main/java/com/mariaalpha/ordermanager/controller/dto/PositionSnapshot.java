package com.mariaalpha.ordermanager.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PositionSnapshot(
    String symbol,
    BigDecimal netQuantity,
    BigDecimal avgEntryPrice,
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl,
    BigDecimal lastMarkPrice,
    Instant timestamp) {}
