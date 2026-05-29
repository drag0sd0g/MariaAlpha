package com.mariaalpha.executionengine.crossing;

import com.mariaalpha.executionengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single midpoint cross emitted by the {@link InternalCrossingEngine}. Each cross corresponds to
 * one aggressing order matching against either a resting counterparty in the internal book ({@code
 * synthetic=false}) or a simulated counterparty conjured by the adapter's liquidity layer ({@code
 * synthetic=true}).
 *
 * <p>{@code spreadBps} captures the prevailing NBBO spread on the underlying market at the moment
 * of the cross — the internalization desk pockets it because the cross happens off-exchange at the
 * midpoint instead of paying-or-collecting at the bid/ask.
 */
public record MidpointCross(
    String aggressorExchangeOrderId,
    String counterpartyExchangeOrderId,
    String symbol,
    Side side,
    int quantity,
    BigDecimal midpoint,
    BigDecimal spreadBps,
    boolean synthetic,
    Instant timestamp) {}
