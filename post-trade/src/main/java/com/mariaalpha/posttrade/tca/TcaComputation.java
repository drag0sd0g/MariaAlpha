package com.mariaalpha.posttrade.tca;

import java.math.BigDecimal;

public record TcaComputation(
    BigDecimal slippageBps,
    BigDecimal implShortfallBps,
    BigDecimal vwapBenchmarkBps,
    BigDecimal spreadCostBps) {}
