package com.mariaalpha.posttrade.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FillForReconRecord(
    UUID fillId,
    UUID orderId,
    String clientOrderId,
    String exchangeOrderId,
    String symbol,
    Side side,
    BigDecimal fillPrice,
    BigDecimal fillQuantity,
    BigDecimal commission,
    String venue,
    String exchangeFillId,
    Instant filledAt) {}
