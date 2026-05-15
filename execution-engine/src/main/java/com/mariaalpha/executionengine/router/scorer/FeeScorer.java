package com.mariaalpha.executionengine.router.scorer;

import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import org.springframework.stereotype.Component;

@Component
public class FeeScorer implements VenueScoreCriterion {
  @Override
  public String name() {
    return "Fees";
  }

  @Override
  public double score(ScoringContext ctx) {
    var aggressive = isAggressive(ctx.order(), ctx.marketState());
    var effectiveBps = aggressive ? ctx.venue().takerFeeBps() : -ctx.venue().makerRebateBps();
    var max = Math.max(1, ctx.config().maxFeeBps());
    var raw = 1.0 - ((double) effectiveBps / max);
    return Math.clamp(raw, 0.0, 1.0);
  }

  static boolean isAggressive(Order order, MarketState marketState) {
    if (order.getOrderType() == OrderType.MARKET || order.getOrderType() == OrderType.STOP) {
      return true;
    }
    if (order.getOrderType() != OrderType.LIMIT
        || order.getLimitPrice() == null
        || marketState == null) {
      return false;
    }
    if (order.getSide() == Side.BUY && marketState.askPrice() != null) {
      return order.getLimitPrice().compareTo(marketState.askPrice()) >= 0;
    }
    if (order.getSide() == Side.SELL && marketState.bidPrice() != null) {
      return order.getLimitPrice().compareTo(marketState.bidPrice()) <= 0;
    }
    return false;
  }
}
