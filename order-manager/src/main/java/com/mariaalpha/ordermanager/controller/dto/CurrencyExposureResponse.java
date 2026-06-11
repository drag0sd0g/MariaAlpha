package com.mariaalpha.ordermanager.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Aggregated portfolio exposure broken down per ISO-4217 currency code. Each {@link Row} represents
 * the sum of position exposures for symbols that resolve (via {@link
 * com.mariaalpha.ordermanager.config.CurrencyConfig}) to that currency at the time of the request.
 * Exposures are reported in their native currency — no FX conversion is performed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrencyExposureResponse(List<Row> rows, int openPositions, Instant asOf) {

  /**
   * Per-currency aggregation: {@code grossExposure} sums {@code |netQty × mark|} across symbols in
   * this currency; {@code netExposure} sums the signed product (longs minus shorts). {@code
   * realizedPnl} and {@code unrealizedPnl} are the corresponding column sums from the position
   * table — useful for FX-aware P&L attribution downstream.
   */
  public record Row(
      String currency,
      int positionCount,
      BigDecimal grossExposure,
      BigDecimal netExposure,
      BigDecimal realizedPnl,
      BigDecimal unrealizedPnl,
      BigDecimal totalPnl) {}
}
