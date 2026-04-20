package com.mariaalpha.ordermanager.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortfolioSummaryResponse(
    BigDecimal totalValue,
    BigDecimal cashBalance,
    BigDecimal grossExposure,
    BigDecimal netExposure,
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl,
    BigDecimal totalPnl,
    int openPositions,
    Instant asOf) {}
