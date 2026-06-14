package com.mariaalpha.executionengine.controller.dto;

import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.TimeInForce;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * One leg of a {@link BasketOrderRequest}. Intentionally a strict subset of {@link
 * SubmitOrderRequest}: basket legs are plain orders ({@code MARKET}/{@code LIMIT}/{@code
 * STOP}/{@code IOC}/{@code FOK}/{@code GTC}). The parent-managed order types ({@code ICEBERG},
 * {@code PEGGED}) are rejected by {@code BasketTradingService} because they need their own
 * per-order coordinator state that a basket does not carry.
 */
public record BasketLegRequest(
    @NotBlank String symbol,
    @NotNull Side side,
    @NotNull OrderType orderType,
    @Min(1) int quantity,
    @DecimalMin("0.01") BigDecimal limitPrice,
    @DecimalMin("0.01") BigDecimal stopPrice,
    TimeInForce tif) {}
