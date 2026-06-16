package com.mariaalpha.apigateway.fix;

import java.math.BigDecimal;

public record FixOrderSubmission(
    String clOrdId,
    String symbol,
    String side,
    String orderType,
    int quantity,
    BigDecimal limitPrice,
    BigDecimal stopPrice,
    String timeInForce) {}
