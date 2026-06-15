package com.mariaalpha.ordermanager.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrencyExposureResponse(List<Row> rows, int openPositions, Instant asOf) {

  public record Row(
      String currency,
      int positionCount,
      BigDecimal grossExposure,
      BigDecimal netExposure,
      BigDecimal realizedPnl,
      BigDecimal unrealizedPnl,
      BigDecimal totalPnl) {}
}
