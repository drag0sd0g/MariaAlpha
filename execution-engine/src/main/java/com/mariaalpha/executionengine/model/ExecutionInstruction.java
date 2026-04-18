package com.mariaalpha.executionengine.model;

import java.math.BigDecimal;

public record ExecutionInstruction(
    Order order, String timeInForce, BigDecimal adjustedLimitPrice) {}
