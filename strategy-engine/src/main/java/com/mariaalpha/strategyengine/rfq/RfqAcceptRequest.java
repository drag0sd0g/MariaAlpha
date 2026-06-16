package com.mariaalpha.strategyengine.rfq;

import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.util.UUID;

public record RfqAcceptRequest(UUID quoteId, Side side, BigDecimal price) {}
