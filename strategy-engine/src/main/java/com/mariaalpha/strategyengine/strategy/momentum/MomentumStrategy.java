package com.mariaalpha.strategyengine.strategy.momentum;

import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MomentumStrategy implements TradingStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(MomentumStrategy.class);
  private static final String NAME = "MOMENTUM";
  private static final double NEUTRAL_RSI = 50.0;

  private enum Cross {
    NONE,
    BULLISH,
    BEARISH
  }

  private enum Position {
    FLAT,
    LONG,
    SHORT
  }

  private int fastPeriod = 20;
  private int slowPeriod = 50;
  private int rsiPeriod = 14;
  private double rsiOverbought = 70.0;
  private double rsiOversold = 30.0;
  private double volumeMultiplier = 1.5;
  private int volumeLookback = 20;
  private int tradeQuantity = 100;
  private Side side = Side.BUY;
  private double stopLossPct = 2.0;

  private int warmupTrades = 0;

  private final Deque<Long> volumeWindow = new ArrayDeque<>();
  private boolean emaSeeded;
  private double fastEma;
  private double slowEma;
  private Boolean fastAboveSlow;
  private double rsi = NEUTRAL_RSI;
  private double avgGain;
  private double avgLoss;
  private int deltaCount;
  private double seedGainSum;
  private double seedLossSum;
  private boolean hasPrevPrice;
  private double prevPrice;
  private long tradesObserved;
  private boolean lastTradeVolumeConfirmed;

  private Cross pendingCross = Cross.NONE;
  private Position position = Position.FLAT;
  private double entryPrice;
  private double lastPrice;
  private MarketTick latestTick;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public synchronized void onTick(MarketTick tick) {
    this.latestTick = tick;
    updateLastPrice(tick);
    if (tick.eventType() == EventType.TRADE) {
      ingestTrade(tick);
    }
  }

  private void ingestTrade(MarketTick tick) {
    tradesObserved++;
    double price = tick.price().doubleValue();

    if (hasPrevPrice) {
      updateRsi(price - prevPrice);
    }

    lastTradeVolumeConfirmed = volumeConfirmed(tick.size());
    pushVolume(tick.size());

    updateEmas(price);

    boolean above = fastEma > slowEma;
    if (fastAboveSlow != null && above != fastAboveSlow && tradesObserved >= effectiveWarmup()) {
      pendingCross = above ? Cross.BULLISH : Cross.BEARISH;
    }
    fastAboveSlow = above;

    prevPrice = price;
    hasPrevPrice = true;
  }

  @Override
  public synchronized Optional<OrderSignal> evaluate(String symbol) {
    if (latestTick == null || tradeQuantity <= 0 || !symbol.equals(latestTick.symbol())) {
      return Optional.empty();
    }

    Cross cross = pendingCross;
    pendingCross = Cross.NONE;

    return switch (position) {
      case FLAT -> tryEnter(symbol, cross);
      case LONG -> tryExitLong(symbol, cross);
      case SHORT -> tryExitShort(symbol, cross);
    };
  }

  private Optional<OrderSignal> tryEnter(String symbol, Cross cross) {
    if (side == Side.BUY
        && cross == Cross.BULLISH
        && rsi < rsiOverbought
        && lastTradeVolumeConfirmed) {
      var limit = resolveLimitPrice(Side.BUY);
      entryPrice = limit.doubleValue();
      position = Position.LONG;
      LOG.info(
          "MOMENTUM entry LONG {} {} shares at {} (fastEma={}, slowEma={}, rsi={})",
          symbol,
          tradeQuantity,
          limit,
          fastEma,
          slowEma,
          rsi);
      return Optional.of(buildSignal(symbol, Side.BUY, OrderType.LIMIT, limit));
    }
    if (side == Side.SELL
        && cross == Cross.BEARISH
        && rsi > rsiOversold
        && lastTradeVolumeConfirmed) {
      var limit = resolveLimitPrice(Side.SELL);
      entryPrice = limit.doubleValue();
      position = Position.SHORT;
      LOG.info(
          "MOMENTUM entry SHORT {} {} shares at {} (fastEma={}, slowEma={}, rsi={})",
          symbol,
          tradeQuantity,
          limit,
          fastEma,
          slowEma,
          rsi);
      return Optional.of(buildSignal(symbol, Side.SELL, OrderType.LIMIT, limit));
    }
    return Optional.empty();
  }

  private Optional<OrderSignal> tryExitLong(String symbol, Cross cross) {
    String reason = longExitReason(cross);
    if (reason == null) {
      return Optional.empty();
    }
    position = Position.FLAT;
    entryPrice = 0.0;
    LOG.info("MOMENTUM exit LONG {} {} shares at MARKET ({})", symbol, tradeQuantity, reason);
    return Optional.of(buildSignal(symbol, Side.SELL, OrderType.MARKET, null));
  }

  private Optional<OrderSignal> tryExitShort(String symbol, Cross cross) {
    String reason = shortExitReason(cross);
    if (reason == null) {
      return Optional.empty();
    }
    position = Position.FLAT;
    entryPrice = 0.0;
    LOG.info("MOMENTUM exit SHORT {} {} shares at MARKET ({})", symbol, tradeQuantity, reason);
    return Optional.of(buildSignal(symbol, Side.BUY, OrderType.MARKET, null));
  }

  private String longExitReason(Cross cross) {
    if (cross == Cross.BEARISH) {
      return "bearish crossover";
    }
    if (rsi >= rsiOverbought) {
      return "RSI overbought";
    }
    if (stopLossTripped(true)) {
      return "stop-loss";
    }
    return null;
  }

  private String shortExitReason(Cross cross) {
    if (cross == Cross.BULLISH) {
      return "bullish crossover";
    }
    if (rsi <= rsiOversold) {
      return "RSI oversold";
    }
    if (stopLossTripped(false)) {
      return "stop-loss";
    }
    return null;
  }

  private boolean stopLossTripped(boolean isLong) {
    if (stopLossPct <= 0.0 || entryPrice <= 0.0 || lastPrice <= 0.0) {
      return false;
    }
    double move = stopLossPct / 100.0;
    return isLong ? lastPrice <= entryPrice * (1.0 - move) : lastPrice >= entryPrice * (1.0 + move);
  }

  @Override
  public synchronized Map<String, Object> getParameters() {
    return Map.ofEntries(
        Map.entry("fastPeriod", fastPeriod),
        Map.entry("slowPeriod", slowPeriod),
        Map.entry("rsiPeriod", rsiPeriod),
        Map.entry("rsiOverbought", rsiOverbought),
        Map.entry("rsiOversold", rsiOversold),
        Map.entry("volumeMultiplier", volumeMultiplier),
        Map.entry("volumeLookback", volumeLookback),
        Map.entry("tradeQuantity", tradeQuantity),
        Map.entry("side", side.name()),
        Map.entry("stopLossPct", stopLossPct),
        Map.entry("warmupTrades", warmupTrades),
        Map.entry("position", position.name()),
        Map.entry("tradesObserved", tradesObserved),
        Map.entry("fastEma", round(fastEma)),
        Map.entry("slowEma", round(slowEma)),
        Map.entry("rsi", round(rsi)));
  }

  @Override
  public synchronized void updateParameters(Map<String, Object> params) {
    if (params.containsKey("fastPeriod")) {
      this.fastPeriod = ((Number) params.get("fastPeriod")).intValue();
    }
    if (params.containsKey("slowPeriod")) {
      this.slowPeriod = ((Number) params.get("slowPeriod")).intValue();
    }
    if (params.containsKey("rsiPeriod")) {
      this.rsiPeriod = ((Number) params.get("rsiPeriod")).intValue();
    }
    if (params.containsKey("rsiOverbought")) {
      this.rsiOverbought = ((Number) params.get("rsiOverbought")).doubleValue();
    }
    if (params.containsKey("rsiOversold")) {
      this.rsiOversold = ((Number) params.get("rsiOversold")).doubleValue();
    }
    if (params.containsKey("volumeMultiplier")) {
      this.volumeMultiplier = ((Number) params.get("volumeMultiplier")).doubleValue();
    }
    if (params.containsKey("volumeLookback")) {
      this.volumeLookback = ((Number) params.get("volumeLookback")).intValue();
    }
    if (params.containsKey("tradeQuantity")) {
      this.tradeQuantity = ((Number) params.get("tradeQuantity")).intValue();
    }
    if (params.containsKey("side")) {
      this.side = Side.valueOf((String) params.get("side"));
    }
    if (params.containsKey("stopLossPct")) {
      this.stopLossPct = ((Number) params.get("stopLossPct")).doubleValue();
    }
    if (params.containsKey("warmupTrades")) {
      this.warmupTrades = ((Number) params.get("warmupTrades")).intValue();
    }
    resetState();
  }

  private int effectiveWarmup() {
    return warmupTrades > 0 ? warmupTrades : Math.max(slowPeriod, 1);
  }

  private void updateRsi(double delta) {
    if (rsiPeriod <= 0) {
      rsi = NEUTRAL_RSI;
      return;
    }
    double gain = delta > 0 ? delta : 0.0;
    double loss = delta < 0 ? -delta : 0.0;
    deltaCount++;
    if (deltaCount <= rsiPeriod) {
      seedGainSum += gain;
      seedLossSum += loss;
      if (deltaCount == rsiPeriod) {
        avgGain = seedGainSum / rsiPeriod;
        avgLoss = seedLossSum / rsiPeriod;
        rsi = computeRsi();
      } else {
        rsi = NEUTRAL_RSI;
      }
      return;
    }
    avgGain = (avgGain * (rsiPeriod - 1) + gain) / rsiPeriod;
    avgLoss = (avgLoss * (rsiPeriod - 1) + loss) / rsiPeriod;
    rsi = computeRsi();
  }

  private double computeRsi() {
    if (avgLoss == 0.0) {
      return 100.0;
    }
    return 100.0 - 100.0 / (1.0 + avgGain / avgLoss);
  }

  private void updateEmas(double price) {
    if (!emaSeeded) {
      fastEma = price;
      slowEma = price;
      emaSeeded = true;
      return;
    }
    double fastAlpha = 2.0 / (fastPeriod + 1);
    double slowAlpha = 2.0 / (slowPeriod + 1);
    fastEma = fastAlpha * price + (1.0 - fastAlpha) * fastEma;
    slowEma = slowAlpha * price + (1.0 - slowAlpha) * slowEma;
  }

  private boolean volumeConfirmed(long size) {
    if (volumeMultiplier <= 0.0) {
      return true;
    }
    if (volumeWindow.isEmpty()) {
      return false;
    }
    double avg = volumeWindow.stream().mapToLong(Long::longValue).average().orElse(0.0);
    return size > volumeMultiplier * avg;
  }

  private void pushVolume(long size) {
    volumeWindow.addLast(size);
    while (volumeWindow.size() > Math.max(volumeLookback, 1)) {
      volumeWindow.removeFirst();
    }
  }

  private void updateLastPrice(MarketTick tick) {
    if (tick.eventType() == EventType.TRADE && tick.price().signum() > 0) {
      lastPrice = tick.price().doubleValue();
      return;
    }
    var bid = tick.bidPrice();
    var ask = tick.askPrice();
    if (bid.signum() > 0 && ask.signum() > 0) {
      lastPrice = bid.add(ask).doubleValue() / 2.0;
    } else if (tick.price().signum() > 0) {
      lastPrice = tick.price().doubleValue();
    }
  }

  private BigDecimal resolveLimitPrice(Side orderSide) {
    if (orderSide == Side.BUY) {
      var ask = latestTick.askPrice();
      return ask.signum() > 0 ? ask : latestTick.price();
    }
    var bid = latestTick.bidPrice();
    return bid.signum() > 0 ? bid : latestTick.price();
  }

  private OrderSignal buildSignal(
      String symbol, Side orderSide, OrderType orderType, BigDecimal limitPrice) {
    return new OrderSignal(
        symbol, orderSide, tradeQuantity, orderType, limitPrice, NAME, latestTick.timestamp());
  }

  private static double round(double value) {
    return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
  }

  private void resetState() {
    volumeWindow.clear();
    emaSeeded = false;
    fastEma = 0.0;
    slowEma = 0.0;
    fastAboveSlow = null;
    rsi = NEUTRAL_RSI;
    avgGain = 0.0;
    avgLoss = 0.0;
    deltaCount = 0;
    seedGainSum = 0.0;
    seedLossSum = 0.0;
    hasPrevPrice = false;
    prevPrice = 0.0;
    tradesObserved = 0;
    lastTradeVolumeConfirmed = false;
    pendingCross = Cross.NONE;
    position = Position.FLAT;
    entryPrice = 0.0;
    lastPrice = 0.0;
    latestTick = null;
  }
}
