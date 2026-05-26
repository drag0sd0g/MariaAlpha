package com.mariaalpha.strategyengine.strategy.twap;

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

/**
 * Time-Weighted Average Price execution strategy.
 *
 * <p>Splits the trading window {@code [startTime, endTime)} into {@code numSlices} equal-duration
 * intervals and distributes the parent quantity evenly across them. As the market clock (driven by
 * tick timestamps, not the wall clock) crosses into each slice, a single LIMIT child order is
 * emitted at the current best ask (buys) or best bid (sells). Any quantity still unexecuted when
 * the window closes is swept with a final MARKET order.
 *
 * <p>This mirrors {@link com.mariaalpha.strategyengine.strategy.vwap.VwapStrategy} but replaces the
 * historical volume profile with equal time slicing — see {@code docs/twap-strategy-explainer.md}.
 */
@Component
public class TwapStrategy implements TradingStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(TwapStrategy.class);
  private static final String NAME = "TWAP";
  static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
  private static final int DEFAULT_NUM_SLICES = 12;

  // Configuration parameters
  private volatile int targetQuantity;
  private Side side = Side.BUY;
  private LocalTime startTime = LocalTime.of(9, 30);
  private LocalTime endTime = LocalTime.of(16, 0);
  private volatile int numSlices = DEFAULT_NUM_SLICES;

  // Derived schedule: equal-duration slices spanning [startTime, endTime)
  private final List<TwapSlice> slices = new ArrayList<>();

  // Computed allocations: sliceIndex -> share count
  private final List<Integer> sliceAllocations = new ArrayList<>();

  // Execution state: tracks which slices have emitted signals (at-most-once)
  private final ConcurrentHashMap<Integer, Boolean> sliceExecuted = new ConcurrentHashMap<>();
  private volatile MarketTick latestTick;
  private volatile boolean completed;

  public TwapStrategy() {
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

    // Past end time: sweep remaining quantity
    if (!marketTime.isBefore(endTime)) {
      return emitSweep(symbol);
    }

    // Find current slice
    int sliceIndex = findSliceIndex(marketTime);
    if (sliceIndex < 0) {
      return Optional.empty();
    }

    // Check if current slice already executed (at-most-once)
    if (sliceExecuted.putIfAbsent(sliceIndex, Boolean.TRUE) != null) {
      return Optional.empty();
    }

    int qty = sliceAllocations.get(sliceIndex);
    if (qty <= 0) {
      return Optional.empty();
    }

    var limitPrice = resolveLimitPrice();
    LOG.info(
        "TWAP slice {} emitting {} {} {} shares at {}", sliceIndex, side, symbol, qty, limitPrice);

    return Optional.of(
        new OrderSignal(
            symbol, side, qty, OrderType.LIMIT, limitPrice, NAME, latestTick.timestamp()));
  }

  @Override
  public Map<String, Object> getParameters() {
    return Map.of(
        "targetQuantity", targetQuantity,
        "side", side.name(),
        "startTime", startTime.toString(),
        "endTime", endTime.toString(),
        "numSlices", numSlices,
        "slices", List.copyOf(slices),
        "executedSlices", sliceExecuted.size(),
        "totalSlices", slices.size());
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
    rebuildSchedule();
    computeAllocations();
    resetExecutionState();
  }

  /**
   * Rebuilds the equal-duration slice schedule from {@code startTime}, {@code endTime}, and {@code
   * numSlices}. Boundaries are computed in whole seconds from the window start so they never drift
   * past {@code endTime}; the final boundary lands exactly on {@code endTime}. Produces no slices
   * when the window is empty/inverted or {@code numSlices <= 0}.
   */
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
      slices.add(new TwapSlice(boundary, nextBoundary));
      boundary = nextBoundary;
    }
  }

  /**
   * Computes per-slice share allocations by dividing the target quantity equally. The last slice
   * absorbs any rounding remainder so the allocations always sum to {@code targetQuantity}.
   */
  private void computeAllocations() {
    sliceAllocations.clear();
    if (slices.isEmpty() || targetQuantity <= 0) {
      return;
    }
    int perSlice = (int) Math.round((double) targetQuantity / slices.size());
    int allocated = 0;
    for (var i = 0; i < slices.size() - 1; i++) {
      sliceAllocations.add(perSlice);
      allocated += perSlice;
    }
    sliceAllocations.add(targetQuantity - allocated);
  }

  /** Resets execution state for a fresh run. */
  private void resetExecutionState() {
    sliceExecuted.clear();
    latestTick = null;
    completed = false;
  }

  /** Resolves the limit price from the latest tick based on side. */
  private BigDecimal resolveLimitPrice() {
    if (side == Side.BUY) {
      // buy at the ask
      var ask = latestTick.askPrice();
      return ask.compareTo(BigDecimal.ZERO) > 0 ? ask : latestTick.price();
    }
    // sell at the bid
    var bid = latestTick.bidPrice();
    return bid.compareTo(BigDecimal.ZERO) > 0 ? bid : latestTick.price();
  }

  /** Computes the total remaining (unexecuted) quantity. */
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

  /** Finds the slice index for the given market time. Returns -1 if not in any slice. */
  private int findSliceIndex(LocalTime marketTime) {
    for (var i = 0; i < slices.size(); i++) {
      var slice = slices.get(i);
      if (!marketTime.isBefore(slice.startTime()) && marketTime.isBefore(slice.endTime())) {
        return i;
      }
    }
    return -1;
  }

  /** Emits a MARKET sweep for any remaining unexecuted quantity. */
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
    LOG.info("TWAP sweep: {} {} {} shares at MARKET", side, symbol, remaining);
    return Optional.of(
        new OrderSignal(
            symbol, side, remaining, OrderType.MARKET, null, NAME, latestTick.timestamp()));
  }
}
