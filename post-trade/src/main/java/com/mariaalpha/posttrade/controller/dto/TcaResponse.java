package com.mariaalpha.posttrade.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mariaalpha.posttrade.entity.TcaResultEntity;
import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TcaResponse(
    UUID tcaId,
    UUID orderId,
    String symbol,
    String strategy,
    Side side,
    BigDecimal quantity,
    BigDecimal slippageBps,
    BigDecimal implShortfallBps,
    BigDecimal vwapBenchmarkBps,
    BigDecimal spreadCostBps,
    BigDecimal arrivalPrice,
    BigDecimal arrivalBidPrice,
    BigDecimal arrivalAskPrice,
    BigDecimal realizedAvgPrice,
    BigDecimal vwapBenchmarkPrice,
    BigDecimal commissionTotal,
    Long executionDurationMs,
    Instant computedAt) {

  public static TcaResponse of(TcaResultEntity e) {
    return new TcaResponse(
        e.getTcaId(),
        e.getOrderId(),
        e.getSymbol(),
        e.getStrategy(),
        e.getSide(),
        e.getQuantity(),
        e.getSlippageBps(),
        e.getImplShortfallBps(),
        e.getVwapBenchmarkBps(),
        e.getSpreadCostBps(),
        e.getArrivalPrice(),
        e.getArrivalBidPrice(),
        e.getArrivalAskPrice(),
        e.getRealizedAvgPrice(),
        e.getVwapBenchmarkPrice(),
        e.getCommissionTotal(),
        e.getExecutionDurationMs(),
        e.getComputedAt());
  }
}
