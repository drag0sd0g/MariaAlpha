package com.mariaalpha.posttrade.tca;

import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public final class TcaCalculator {

  private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
  private static final int SCALE = 4;
  private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000L);
  private static final BigDecimal TWO = BigDecimal.valueOf(2L);

  private TcaCalculator() {}

  public static TcaComputation compute(TcaInputs inputs) {
    BigDecimal slippage =
        slippageBps(inputs.side(), inputs.realizedAvgPrice(), inputs.arrivalMidPrice());
    BigDecimal implShortfall =
        implShortfallBps(
            inputs.side(),
            inputs.realizedAvgPrice(),
            inputs.arrivalMidPrice(),
            inputs.quantity(),
            inputs.commissionTotal());
    BigDecimal vwapBench =
        vwapBenchmarkBps(inputs.side(), inputs.realizedAvgPrice(), inputs.vwapBenchmarkPrice());
    BigDecimal spreadCost =
        spreadCostBps(inputs.arrivalBidPrice(), inputs.arrivalAskPrice(), inputs.arrivalMidPrice());
    return new TcaComputation(slippage, implShortfall, vwapBench, spreadCost);
  }

  static BigDecimal slippageBps(Side side, BigDecimal avgFill, BigDecimal arrival) {
    if (avgFill == null || arrival == null || isZero(arrival)) {
      return null;
    }
    BigDecimal delta = signedCost(side, avgFill, arrival);
    return delta
        .divide(arrival, MC)
        .multiply(TEN_THOUSAND, MC)
        .setScale(SCALE, RoundingMode.HALF_UP);
  }

  static BigDecimal implShortfallBps(
      Side side,
      BigDecimal avgFill,
      BigDecimal arrival,
      BigDecimal quantity,
      BigDecimal commissionTotal) {
    if (avgFill == null
        || arrival == null
        || quantity == null
        || isZero(arrival)
        || isZero(quantity)) {
      return null;
    }
    BigDecimal commission = commissionTotal == null ? BigDecimal.ZERO : commissionTotal;
    BigDecimal priceComponent = signedCost(side, avgFill, arrival).multiply(quantity, MC);
    BigDecimal totalCost = priceComponent.add(commission, MC);
    BigDecimal notional = arrival.multiply(quantity, MC);
    return totalCost
        .divide(notional, MC)
        .multiply(TEN_THOUSAND, MC)
        .setScale(SCALE, RoundingMode.HALF_UP);
  }

  static BigDecimal vwapBenchmarkBps(Side side, BigDecimal avgFill, BigDecimal vwap) {
    if (avgFill == null || vwap == null || isZero(vwap)) {
      return null;
    }
    BigDecimal delta = signedCost(side, avgFill, vwap);
    return delta.divide(vwap, MC).multiply(TEN_THOUSAND, MC).setScale(SCALE, RoundingMode.HALF_UP);
  }

  static BigDecimal spreadCostBps(BigDecimal bid, BigDecimal ask, BigDecimal mid) {
    if (bid == null || ask == null || mid == null || isZero(mid)) {
      return null;
    }
    BigDecimal spread = ask.subtract(bid);
    if (spread.signum() < 0) {
      spread = BigDecimal.ZERO;
    }
    return spread
        .divide(mid, MC)
        .divide(TWO, MC)
        .multiply(TEN_THOUSAND, MC)
        .setScale(SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal signedCost(Side side, BigDecimal achieved, BigDecimal benchmark) {
    BigDecimal rawDelta = achieved.subtract(benchmark);
    return side == Side.BUY ? rawDelta : rawDelta.negate();
  }

  private static boolean isZero(BigDecimal v) {
    return v.signum() == 0;
  }
}
