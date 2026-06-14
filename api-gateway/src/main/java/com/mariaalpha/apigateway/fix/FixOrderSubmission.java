package com.mariaalpha.apigateway.fix;

import java.math.BigDecimal;

/**
 * Normalised, broker-neutral view of an inbound FIX {@code NewOrderSingle (35=D)} after
 * translation, ready to be forwarded to execution-engine's {@code POST /api/execution/orders}.
 *
 * <p>The gateway maps plain order types (MARKET / LIMIT / STOP / IOC / FOK / GTC). Algorithmic
 * parent orders (VWAP/TWAP/POV/…) are intentionally <em>not</em> driven from FIX here: their
 * per-strategy parameter shapes (volume profiles, participation rates, slice windows) are not
 * expressible in standard FIX tags, so the REST {@code /api/algo/orders} surface remains the algo
 * entry point. {@code side} / {@code orderType} / {@code timeInForce} are the MariaAlpha enum names
 * so the value serialises straight into {@code SubmitOrderRequest}.
 */
public record FixOrderSubmission(
    String clOrdId,
    String symbol,
    String side,
    String orderType,
    int quantity,
    BigDecimal limitPrice,
    BigDecimal stopPrice,
    String timeInForce) {}
