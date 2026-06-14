package com.mariaalpha.strategyengine.algo;

import com.mariaalpha.strategyengine.model.Side;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Request body for {@code POST /api/algo/orders} — submits a new algorithmic parent order (roadmap
 * 3.4.4). {@code strategyName} must match a registered {@link
 * com.mariaalpha.strategyengine.strategy.TradingStrategy}; {@code parameters} is forwarded verbatim
 * to that strategy's {@code updateParameters} (so it must contain whatever shape that strategy
 * expects — e.g. for VWAP: {@code targetQuantity}, {@code startTime}, {@code endTime}, {@code
 * volumeProfile}).
 */
public record AlgoOrderRequest(
    @NotBlank String symbol,
    @NotNull Side side,
    @Min(1) long targetQuantity,
    @NotBlank String strategyName,
    Map<String, Object> parameters) {}
