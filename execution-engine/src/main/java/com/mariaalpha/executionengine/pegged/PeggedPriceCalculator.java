package com.mariaalpha.executionengine.pegged;

import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class PeggedPriceCalculator {

  private static final int PRICE_SCALE = 4;

  public BigDecimal referencePrice(PegType pegType, Side side, MarketState marketState) {
    if (marketState == null) {
      return null;
    }
    BigDecimal bid = marketState.bidPrice();
    BigDecimal ask = marketState.askPrice();
    return switch (pegType) {
      case MIDPOINT -> {
        if (bid == null || ask == null) {
          yield null;
        }
        yield bid.add(ask).divide(BigDecimal.valueOf(2), PRICE_SCALE, RoundingMode.HALF_UP);
      }
      case PRIMARY -> side == Side.BUY ? bid : ask;
      case MARKET -> side == Side.BUY ? ask : bid;
    };
  }

  public BigDecimal peggedPrice(
      BigDecimal reference, Side side, Integer offsetBps, BigDecimal priceCap) {
    if (reference == null) {
      return null;
    }
    int offset = offsetBps == null ? 0 : offsetBps;
    BigDecimal signedOffsetFraction =
        BigDecimal.valueOf(offset).divide(BigDecimal.valueOf(10_000L), 8, RoundingMode.HALF_UP);
    BigDecimal multiplier =
        side == Side.BUY
            ? BigDecimal.ONE.add(signedOffsetFraction)
            : BigDecimal.ONE.subtract(signedOffsetFraction);
    BigDecimal raw = reference.multiply(multiplier).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    if (priceCap == null) {
      return raw;
    }
    if (side == Side.BUY && raw.compareTo(priceCap) > 0) {
      return priceCap.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
    if (side == Side.SELL && raw.compareTo(priceCap) < 0) {
      return priceCap.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
    return raw;
  }

  public boolean shouldRepeg(BigDecimal previousPrice, BigDecimal newPrice, int thresholdBps) {
    if (previousPrice == null || newPrice == null) {
      return previousPrice == null && newPrice != null;
    }
    if (previousPrice.compareTo(BigDecimal.ZERO) <= 0) {
      return true;
    }
    BigDecimal diff = newPrice.subtract(previousPrice).abs();
    BigDecimal diffBps =
        diff.multiply(BigDecimal.valueOf(10_000L)).divide(previousPrice, 4, RoundingMode.HALF_UP);
    return diffBps.compareTo(BigDecimal.valueOf(thresholdBps)) >= 0;
  }
}
