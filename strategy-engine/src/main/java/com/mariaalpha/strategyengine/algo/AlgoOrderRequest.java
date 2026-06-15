package com.mariaalpha.strategyengine.algo;

import com.mariaalpha.strategyengine.model.Side;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record AlgoOrderRequest(
    @NotBlank String symbol,
    @NotNull Side side,
    @Min(1) long targetQuantity,
    @NotBlank String strategyName,
    Map<String, Object> parameters) {}
