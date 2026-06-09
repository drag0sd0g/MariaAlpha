package com.mariaalpha.posttrade.allocation;

import java.math.BigDecimal;

/**
 * Per-sub-account result of a single allocation run.
 *
 * <p>{@code allocatedQuantity} is a whole-share integer (modelled as {@code BigDecimal} to match
 * the schema and to support future fractional-share workflows). {@code allocatedAvgPrice} is always
 * the parent's executed average price — pro-rata and FIFO both inherit the parent's realised price;
 * only batch/bunched-order workflows would differentiate per-account pricing, which is out of scope
 * for the MVP.
 */
public record AllocationResult(
    String subAccount,
    BigDecimal allocatedQuantity,
    BigDecimal allocatedAvgPrice,
    AllocationMethod method) {}
