package com.mariaalpha.strategyengine.strategy.shortfall;

import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ImplementationShortfallStrategy implements TradingStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(ImplementationShortfallStrategy.class);
  private static final String NAME = "IS";
  static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
  private static final int DEFAULT_NUM_SLICES = 12;
  private static final double DEFAULT_URGENCY = 0.5;

  private volatile int targetQuantity;
  private Side side = Side.BUY;
  private LocalTime startTime = LocalTime.of(9, 30);
  private LocalTime endTime = LocalTime.of(16, 0);
  private volatile int numSlices = DEFAULT_NUM_SLICES;
  private volatile double urgency = DEFAULT_URGENCY;

  private final List<ShortfallSlice> slices = new ArrayList<>();

  private final List<Integer> sliceAllocations = new ArrayList<>();

  private final ConcurrentHashMap<Integer, Boolean> sliceExecuted = new ConcurrentHashMap<>();
  private volatile MarketTick latestTick;
  private volatile boolean completed;

  public ImplementationShortfallStrategy() {
    rebuildSchedule();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public void onTick(MarketTick tick) {
    this.latestTick = tick;
  }

  @Override
  public Optional<OrderSignal> evaluate(String symbol) {
    if (completed || latestTick == null || slices.isEmpty() || targetQuantity <= 0) {
      return Optional.empty();
    }

    if (!symbol.equals(latestTick.symbol())) {
      return Optional.empty();
    }

    var marketTime = latestTick.timestamp().atZone(MARKET_ZONE).toLocalTime();

    if (marketTime.isBefore(startTime)) {
      return Optional.empty();
    }

    if (!marketTime.isBefore(endTime)) {
      return emitSweep(symbol);
    }

    int sliceIndex = findSliceIndex(marketTime);
    if (sliceIndex < 0) {
      return Optional.empty();
    }

    if (sliceExecuted.putIfAbsent(sliceIndex, Boolean.TRUE) != null) {
      return Optional.empty();
    }

    int qty = sliceAllocations.get(sliceIndex);
    if (qty <= 0) {
      return Optional.empty();
    }

    var limitPrice = resolveLimitPrice();
    LOG.info(
        "IS slice {} emitting {} {} {} shares at {} (urgency={})",
        sliceIndex,
        side,
        symbol,
        qty,
        limitPrice,
        urgency);

    return Optional.of(
        new OrderSignal(
            symbol, side, qty, OrderType.LIMIT, limitPrice, NAME, latestTick.timestamp()));
  }

  @Override
  public Map<String, Object> getParameters() {
    return Map.ofEntries(
        Map.entry("targetQuantity", targetQuantity),
        Map.entry("side", side.name()),
        Map.entry("startTime", startTime.toString()),
        Map.entry("endTime", endTime.toString()),
        Map.entry("numSlices", numSlices),
        Map.entry("urgency", urgency),
        Map.entry("slices", List.copyOf(slices)),
        Map.entry("allocations", List.copyOf(sliceAllocations)),
        Map.entry("executedSlices", sliceExecuted.size()),
        Map.entry("totalSlices", slices.size()));
  }

  @Override
  public void updateParameters(Map<String, Object> params) {
    if (params.containsKey("targetQuantity")) {
      this.targetQuantity = ((Number) params.get("targetQuantity")).intValue();
    }
    if (params.containsKey("side")) {
      this.side = Side.valueOf((String) params.get("side"));
    }
    if (params.containsKey("startTime")) {
      this.startTime = LocalTime.parse((String) params.get("startTime"));
    }
    if (params.containsKey("endTime")) {
      this.endTime = LocalTime.parse((String) params.get("endTime"));
    }
    if (params.containsKey("numSlices")) {
      this.numSlices = ((Number) params.get("numSlices")).intValue();
    }
    if (params.containsKey("urgency")) {
      this.urgency = ((Number) params.get("urgency")).doubleValue();
    }
    rebuildSchedule();
    computeAllocations();
    resetExecutionState();
  }

  private void rebuildSchedule() {
    slices.clear();
    long totalSeconds = Duration.between(startTime, endTime).getSeconds();
    if (numSlices <= 0 || totalSeconds <= 0) {
      return;
    }
    var boundary = startTime;
    for (var i = 1; i <= numSlices; i++) {
      long offsetSeconds = Math.round((double) i * totalSeconds / numSlices);
      var nextBoundary = startTime.plusSeconds(offsetSeconds);
      slices.add(new ShortfallSlice(boundary, nextBoundary));
      boundary = nextBoundary;
    }
  }

  private void computeAllocations() {
    sliceAllocations.clear();
    int n = slices.size();
    if (n == 0 || targetQuantity <= 0) {
      return;
    }
    double[] weights = frontLoadWeights(n, urgency);
    int allocated = 0;
    for (var i = 0; i < n - 1; i++) {
      int qty = (int) Math.round(targetQuantity * weights[i]);
      sliceAllocations.add(qty);
      allocated += qty;
    }
    sliceAllocations.add(targetQuantity - allocated);
  }

  private static double[] frontLoadWeights(int n, double urgency) {
    double[] weights = new double[n];
    if (urgency <= 0.0) {
      Arrays.fill(weights, 1.0 / n);
      return weights;
    }
    double k = urgency;
    double denom = 1.0 - Math.exp(-2.0 * k * n);
    double prevHoldings = 1.0;
    for (var i = 0; i < n; i++) {
      double holdings = (Math.exp(-k * (i + 1)) - Math.exp(-k * (2.0 * n - i - 1))) / denom;
      weights[i] = prevHoldings - holdings;
      prevHoldings = holdings;
    }
    return weights;
  }

  private void resetExecutionState() {
    sliceExecuted.clear();
    latestTick = null;
    completed = false;
  }

  private BigDecimal resolveLimitPrice() {
    if (side == Side.BUY) {
      var ask = latestTick.askPrice();
      return ask.compareTo(BigDecimal.ZERO) > 0 ? ask : latestTick.price();
    }
    var bid = latestTick.bidPrice();
    return bid.compareTo(BigDecimal.ZERO) > 0 ? bid : latestTick.price();
  }

  private int remainingQuantity() {
    int executed = 0;
    for (var entry : sliceExecuted.entrySet()) {
      int idx = entry.getKey();
      if (idx >= 0 && idx < sliceAllocations.size()) {
        executed += sliceAllocations.get(idx);
      }
    }
    return targetQuantity - executed;
  }

  private int findSliceIndex(LocalTime marketTime) {
    for (var i = 0; i < slices.size(); i++) {
      var slice = slices.get(i);
      if (!marketTime.isBefore(slice.startTime()) && marketTime.isBefore(slice.endTime())) {
        return i;
      }
    }
    return -1;
  }

  private Optional<OrderSignal> emitSweep(String symbol) {
    if (completed) {
      return Optional.empty();
    }
    int remaining = remainingQuantity();
    if (remaining <= 0) {
      completed = true;
      return Optional.empty();
    }
    completed = true;
    LOG.info("IS sweep: {} {} {} shares at MARKET", side, symbol, remaining);
    return Optional.of(
        new OrderSignal(
            symbol, side, remaining, OrderType.MARKET, null, NAME, latestTick.timestamp()));
  }
}
