package com.mariaalpha.executionengine.router.scorer;

import com.mariaalpha.executionengine.config.SorConfig;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.router.Venue;

public record ScoringContext(Order order, Venue venue, MarketState marketState, SorConfig config) {}
