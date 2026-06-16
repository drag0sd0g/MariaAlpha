package com.mariaalpha.strategyengine.strategy.close;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CloseStrategy implements TradingStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(CloseStrategy.class);
  private static final String NAME = "CLOSE";
  static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
  private static final int DEFAULT_NUM_PRE_CLOSE_SLICES = 6;
  private static final double DEFAULT_PRE_CLOSE_FRACTION = 0.30;
  private static final int DEFAULT_MOC_OFFSET_MINUTES = 5;

  private volatile int targetQuantity;
  private Side side = Side.BUY;
  private LocalTime windowStart = LocalTime.of(15, 30);
  private LocalTime closeTime = LocalTime.of(16, 0);
  private volatile int mocOffsetMinutes = DEFAULT_MOC_OFFSET_MINUTES;
  private volatile double preCloseFraction = DEFAULT_PRE_CLOSE_FRACTION;
  private volatile int numPreCloseSlices = DEFAULT_NUM_PRE_CLOSE_SLICES;

  private final List<CloseSlice> preCloseSlices = new ArrayList<>();
  private final List<Integer> sliceAllocations = new ArrayList<>();
  private volatile int mocAllocation;

  private final ConcurrentHashMap<Integer, Boolean> sliceExecuted = new ConcurrentHashMap<>();
  private volatile boolean mocFired;
  private volatile MarketTick latestTick;
  private volatile boolean completed;

  public CloseStrategy() {
    rebuildSchedule();
    computeAllocations();
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
    if (completed || latestTick == null || targetQuantity <= 0) {
      return Optional.empty();
    }
    if (!symbol.equals(latestTick.symbol())) {
      return Optional.empty();
    }

    var marketTime = latestTick.timestamp().atZone(MARKET_ZONE).toLocalTime();

    if (marketTime.isBefore(windowStart)) {
      return Optional.empty();
    }

    if (!marketTime.isBefore(closeTime)) {
      return emitSweep(symbol);
    }

    var mocCutoff = mocCutoff();
    if (!marketTime.isBefore(mocCutoff)) {
      return emitMoc(symbol);
    }

    int sliceIndex = findSliceIndex(marketTime);
    if (sliceIndex < 0 || sliceIndex >= sliceAllocations.size()) {
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
        "CLOSE pre-close slice {} emitting {} {} {} shares at {}",
        sliceIndex,
        side,
        symbol,
        qty,
        limitPrice);
    return Optional.of(
        new OrderSignal(
            symbol, side, qty, OrderType.LIMIT, limitPrice, NAME, latestTick.timestamp()));
  }

  @Override
  public Map<String, Object> getParameters() {
    return Map.ofEntries(
        Map.entry("targetQuantity", targetQuantity),
        Map.entry("side", side.name()),
        Map.entry("windowStart", windowStart.toString()),
        Map.entry("closeTime", closeTime.toString()),
        Map.entry("mocOffsetMinutes", mocOffsetMinutes),
        Map.entry("preCloseFraction", preCloseFraction),
        Map.entry("numPreCloseSlices", numPreCloseSlices),
        Map.entry("preCloseSlices", List.copyOf(preCloseSlices)),
        Map.entry("sliceAllocations", List.copyOf(sliceAllocations)),
        Map.entry("mocAllocation", mocAllocation),
        Map.entry("executedPreCloseSlices", sliceExecuted.size()),
        Map.entry("totalPreCloseSlices", preCloseSlices.size()),
        Map.entry("mocFired", mocFired));
  }

  @Override
  public void updateParameters(Map<String, Object> params) {
    if (params.containsKey("targetQuantity")) {
      this.targetQuantity = ((Number) params.get("targetQuantity")).intValue();
    }
    if (params.containsKey("side")) {
      this.side = Side.valueOf((String) params.get("side"));
    }
    if (params.containsKey("windowStart")) {
      this.windowStart = LocalTime.parse((String) params.get("windowStart"));
    }
    if (params.containsKey("closeTime")) {
      this.closeTime = LocalTime.parse((String) params.get("closeTime"));
    }
    if (params.containsKey("mocOffsetMinutes")) {
      this.mocOffsetMinutes = ((Number) params.get("mocOffsetMinutes")).intValue();
    }
    if (params.containsKey("preCloseFraction")) {
      this.preCloseFraction = ((Number) params.get("preCloseFraction")).doubleValue();
    }
    if (params.containsKey("numPreCloseSlices")) {
      this.numPreCloseSlices = ((Number) params.get("numPreCloseSlices")).intValue();
    }
    rebuildSchedule();
    computeAllocations();
    resetExecutionState();
  }

  private LocalTime mocCutoff() {
    var cutoff = closeTime.minusMinutes(Math.max(0, mocOffsetMinutes));
    return cutoff.isBefore(windowStart) ? windowStart : cutoff;
  }

  private void rebuildSchedule() {
    preCloseSlices.clear();
    var cutoff = mocCutoff();
    long totalSeconds = Duration.between(windowStart, cutoff).getSeconds();
    if (numPreCloseSlices <= 0 || totalSeconds <= 0) {
      return;
    }
    var boundary = windowStart;
    for (var i = 1; i <= numPreCloseSlices; i++) {
      long offsetSeconds = Math.round((double) i * totalSeconds / numPreCloseSlices);
      var nextBoundary = windowStart.plusSeconds(offsetSeconds);
      preCloseSlices.add(new CloseSlice(boundary, nextBoundary));
      boundary = nextBoundary;
    }
  }

  private void computeAllocations() {
    sliceAllocations.clear();
    mocAllocation = 0;
    int n = preCloseSlices.size();
    if (targetQuantity <= 0) {
      return;
    }
    if (n == 0) {
      mocAllocation = targetQuantity;
      return;
    }
    double clampedFraction = Math.max(0.0, Math.min(1.0, preCloseFraction));
    int preCloseTotal = (int) Math.round(clampedFraction * targetQuantity);
    int perSlice = preCloseTotal > 0 ? (int) Math.round((double) preCloseTotal / n) : 0;
    int allocated = 0;
    for (var i = 0; i < n - 1; i++) {
      sliceAllocations.add(perSlice);
      allocated += perSlice;
    }
    sliceAllocations.add(preCloseTotal - allocated);
    mocAllocation = targetQuantity - preCloseTotal;
  }

  private void resetExecutionState() {
    sliceExecuted.clear();
    mocFired = false;
    latestTick = null;
    completed = false;
  }

  private BigDecimal resolveLimitPrice() {
    if (side == Side.BUY) {
      var ask = latestTick.askPrice();
      return ask != null && ask.compareTo(BigDecimal.ZERO) > 0 ? ask : latestTick.price();
    }
    var bid = latestTick.bidPrice();
    return bid != null && bid.compareTo(BigDecimal.ZERO) > 0 ? bid : latestTick.price();
  }

  private int findSliceIndex(LocalTime marketTime) {
    for (var i = 0; i < preCloseSlices.size(); i++) {
      var slice = preCloseSlices.get(i);
      if (!marketTime.isBefore(slice.startTime()) && marketTime.isBefore(slice.endTime())) {
        return i;
      }
    }
    return -1;
  }

  private int sumExecutedFromPreCloseSlices() {
    int executed = 0;
    for (var entry : sliceExecuted.entrySet()) {
      int idx = entry.getKey();
      if (idx >= 0 && idx < sliceAllocations.size()) {
        executed += sliceAllocations.get(idx);
      }
    }
    return executed;
  }

  private Optional<OrderSignal> emitMoc(String symbol) {
    if (mocFired) {
      return Optional.empty();
    }
    mocFired = true;
    int remaining = targetQuantity - sumExecutedFromPreCloseSlices();
    if (remaining <= 0) {
      return Optional.empty();
    }
    LOG.info("CLOSE MOC: {} {} {} shares at MARKET", side, symbol, remaining);
    return Optional.of(
        new OrderSignal(
            symbol, side, remaining, OrderType.MARKET, null, NAME, latestTick.timestamp()));
  }

  private Optional<OrderSignal> emitSweep(String symbol) {
    if (completed) {
      return Optional.empty();
    }
    completed = true;
    if (mocFired) {
      return Optional.empty();
    }
    int remaining = targetQuantity - sumExecutedFromPreCloseSlices();
    if (remaining <= 0) {
      return Optional.empty();
    }
    LOG.info("CLOSE post-close sweep: {} {} {} shares at MARKET", side, symbol, remaining);
    mocFired = true;
    return Optional.of(
        new OrderSignal(
            symbol, side, remaining, OrderType.MARKET, null, NAME, latestTick.timestamp()));
  }
}
