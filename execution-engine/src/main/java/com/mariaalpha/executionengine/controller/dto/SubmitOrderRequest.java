package com.mariaalpha.executionengine.controller.dto;

import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record SubmitOrderRequest(
    @NotBlank String symbol,
    @NotNull Side side,
    @NotNull OrderType orderType,
    @Min(1) int quantity,
    @DecimalMin("0.01") BigDecimal limitPrice,
    @DecimalMin("0.01") BigDecimal stopPrice,
    String clientOrderId) {}
