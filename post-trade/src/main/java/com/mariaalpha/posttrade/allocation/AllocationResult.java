package com.mariaalpha.posttrade.allocation;

import java.math.BigDecimal;

public record AllocationResult(
    String subAccount,
    BigDecimal allocatedQuantity,
    BigDecimal allocatedAvgPrice,
    AllocationMethod method) {}
