package com.mariaalpha.posttrade.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Recon-tailored fill record returned by order-manager's {@code /api/orders/fills/by-date} endpoint
 * — fill fields plus the parent order's exchange/client ids needed to match against external venue
 * activity.
 */
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
