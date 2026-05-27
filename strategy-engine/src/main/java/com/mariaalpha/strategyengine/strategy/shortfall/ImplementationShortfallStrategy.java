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

/**
 * Implementation Shortfall (IS) execution strategy.
 *
 * <p>Like {@link com.mariaalpha.strategyengine.strategy.twap.TwapStrategy}, IS divides the trading
 * window {@code [startTime, endTime)} into {@code numSlices} equal-duration intervals and emits one
 * LIMIT child order as the market clock (driven by tick timestamps, never the wall clock) crosses
 * into each slice, sweeping any leftover with a MARKET order at the deadline. The <em>only</em>
 * difference is how the parent quantity is spread across those slices: TWAP allocates it
 * <b>uniformly</b>, whereas IS <b>front-loads</b> it.
 *
 * <p>Front-loading minimises <em>implementation shortfall</em> — the gap between the decision
 * (arrival) price and the realised average fill price. By trading more aggressively early, IS
 * shrinks the window over which the price can drift away from the arrival mark (timing risk), at
 * the cost of higher temporary market impact. The trade-off is governed by a single dimensionless
 * {@code urgency} knob (κ):
 *
 * <ul>
 *   <li>{@code urgency = 0} → uniform slicing, i.e. exactly TWAP (graceful degradation).
 *   <li>{@code urgency > 0} → strictly decreasing per-slice allocations; the larger κ, the more of
 *       the order is packed into the first few slices.
 * </ul>
 *
 * <p>The allocation curve is the discrete Almgren–Chriss optimal-execution trajectory: the fraction
 * still outstanding after slice {@code i} is {@code h(i) = sinh(κ(N−i)) / sinh(κN)}, so slice
 * {@code i} trades {@code h(i) − h(i+1)}. As κ → 0 every slice tends to {@code 1/N}. See {@code
 * docs/implementation-shortfall-strategy-explainer.md}.
 */
@Component
public class ImplementationShortfallStrategy implements TradingStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(ImplementationShortfallStrategy.class);
  private static final String NAME = "IS";
  static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
  private static final int DEFAULT_NUM_SLICES = 12;
  private static final double DEFAULT_URGENCY = 0.5;

  // Configuration parameters
  private volatile int targetQuantity;
  private Side side = Side.BUY;
  private LocalTime startTime = LocalTime.of(9, 30);
  private LocalTime endTime = LocalTime.of(16, 0);
  private volatile int numSlices = DEFAULT_NUM_SLICES;
  private volatile double urgency = DEFAULT_URGENCY;

  // Derived schedule: equal-duration slices spanning [startTime, endTime)
  private final List<ShortfallSlice> slices = new ArrayList<>();

  // Computed front-loaded allocations: sliceIndex -> share count
  private final List<Integer> sliceAllocations = new ArrayList<>();

  // Execution state: tracks which slices have emitted signals (at-most-once)
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

  /**
   * Rebuilds the equal-duration slice schedule from {@code startTime}, {@code endTime}, and {@code
   * numSlices}. Boundaries are computed in whole seconds from the window start so they never drift
   * past {@code endTime}; the final boundary lands exactly on {@code endTime}. Produces no slices
   * when the window is empty/inverted or {@code numSlices <= 0}. Identical to TWAP's schedule — IS
   * differs only in allocation, not in interval layout.
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
      slices.add(new ShortfallSlice(boundary, nextBoundary));
      boundary = nextBoundary;
    }
  }

  /**
   * Computes per-slice share allocations by front-loading the target quantity along the
   * Almgren–Chriss trajectory (see {@link #frontLoadWeights}). The last slice absorbs any rounding
   * remainder so the allocations always sum exactly to {@code targetQuantity}.
   */
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

  /**
   * Fraction of the parent to trade in each of {@code n} equal time slices, front-loaded by the
   * Almgren–Chriss optimal trajectory. The outstanding-holdings curve is {@code h(m) = sinh(κm) /
   * sinh(κn)}, so slice {@code i} (holdings going from {@code h(n−i)} down to {@code h(n−i−1)})
   * trades {@code h(n−i) − h(n−i−1)}. The weights are strictly decreasing for κ &gt; 0 and sum to
   * 1.
   *
   * <p>A non-positive urgency degrades to uniform {@code 1/n} weights (i.e. TWAP). The hyperbolic
   * sines are evaluated in the numerically stable exponential form (dividing through by {@code
   * e^{κn}/2}) so the computation never overflows even for large {@code κ·n}.
   */
  private static double[] frontLoadWeights(int n, double urgency) {
    double[] weights = new double[n];
    if (urgency <= 0.0) {
      Arrays.fill(weights, 1.0 / n);
      return weights;
    }
    double k = urgency;
    // denom = sinh(κn) normalised by e^{κn}/2 = 1 − e^{−2κn}
    double denom = 1.0 - Math.exp(-2.0 * k * n);
    double prevHoldings = 1.0; // h(n) = 100% outstanding before slice 0
    for (var i = 0; i < n; i++) {
      // holdings after slice i = h(n−i−1) = sinh(κ(n−i−1))/sinh(κn), exp-stable form:
      //   (e^{−κ(i+1)} − e^{−κ(2n−i−1)}) / denom
      double holdings = (Math.exp(-k * (i + 1)) - Math.exp(-k * (2.0 * n - i - 1))) / denom;
      weights[i] = prevHoldings - holdings;
      prevHoldings = holdings;
    }
    return weights;
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
    LOG.info("IS sweep: {} {} {} shares at MARKET", side, symbol, remaining);
    return Optional.of(
        new OrderSignal(
            symbol, side, remaining, OrderType.MARKET, null, NAME, latestTick.timestamp()));
  }
}
