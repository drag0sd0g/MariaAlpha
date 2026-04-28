package com.mariaalpha.posttrade.tca;

import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TcaInputs(
    UUID orderId,
    String symbol,
    String strategy,
    Side side,
    BigDecimal quantity,
    BigDecimal arrivalMidPrice,
    BigDecimal arrivalBidPrice,
    BigDecimal arrivalAskPrice,
    BigDecimal realizedAvgPrice,
    BigDecimal commissionTotal,
    BigDecimal vwapBenchmarkPrice,
    Instant orderStartTs,
    Instant orderEndTs) {}
