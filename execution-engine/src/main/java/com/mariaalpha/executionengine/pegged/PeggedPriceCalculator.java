package com.mariaalpha.executionengine.pegged;

import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Pure computation of the working limit price for a pegged order (roadmap 3.2.3).
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li>{@link #referencePrice} — picks the right side of the NBBO for the requested {@link
 *       PegType}.
 *   <li>{@link #peggedPrice} — applies the signed offset (in bps, aggressive-toward-fill) and the
 *       optional {@code priceCap} (max for BUY, min for SELL).
 * </ol>
 *
 * <p>Separated from {@link PeggedCoordinator} so the math has dedicated unit-test coverage and so
 * the {@link PeggedOrderHandler} preview path can call it without dragging in the coordinator.
 */
@Component
public class PeggedPriceCalculator {

  private static final int PRICE_SCALE = 4;

  /**
   * Resolve the reference price for a {@code (pegType, side, marketState)} triple. Returns null if
   * the requested reference side is unavailable (e.g. one-sided book).
   */
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

  /**
   * Compute the working limit price: reference × (1 ± offset bps), then clamped by the optional
   * {@code priceCap}. Positive offset moves the price toward fill (BUY higher, SELL lower); a
   * negative offset pulls it the other way (more passive). Pass {@code null} or 0 for no offset.
   *
   * @return computed limit price, never null when {@code reference} is non-null
   */
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
    // priceCap is the max for BUY, min for SELL. Clamp accordingly.
    if (side == Side.BUY && raw.compareTo(priceCap) > 0) {
      return priceCap.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
    if (side == Side.SELL && raw.compareTo(priceCap) < 0) {
      return priceCap.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
    return raw;
  }

  /**
   * True if the new price is far enough from the previously-submitted price to warrant cancelling
   * the active child and re-submitting. Threshold is the configured {@code repegThresholdBps}.
   */
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
