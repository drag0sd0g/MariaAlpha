package com.mariaalpha.strategyengine.rfq;

import com.mariaalpha.strategyengine.model.OrderSignal;
import java.util.UUID;

public record RfqAcceptResponse(UUID quoteId, String symbol, OrderSignal signal, String status) {}
