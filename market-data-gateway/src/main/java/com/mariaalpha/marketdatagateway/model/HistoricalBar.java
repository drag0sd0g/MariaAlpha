package com.mariaalpha.marketdatagateway.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record HistoricalBar(
    String symbol,
    LocalDate barDate,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume,
    BigDecimal vwap) {}
