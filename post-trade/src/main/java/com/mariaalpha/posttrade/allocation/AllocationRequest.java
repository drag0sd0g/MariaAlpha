package com.mariaalpha.posttrade.allocation;

import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Service-layer input for an allocation run (roadmap 3.4.2).
 *
 * <p>The controller bridges from a REST DTO into this record; the service+calculator pair never
 * sees the HTTP types. {@code method} is nullable — when null, the {@link SubAccountRegistry}'s
 * configured default is used.
 */
public record AllocationRequest(
    UUID orderId,
    String symbol,
    Side side,
    BigDecimal parentFilledQuantity,
    BigDecimal parentAvgPrice,
    AllocationMethod method) {}
