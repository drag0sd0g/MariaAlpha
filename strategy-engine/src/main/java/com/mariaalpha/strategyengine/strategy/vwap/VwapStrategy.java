package com.mariaalpha.strategyengine.strategy.vwap;

import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.math.BigDecimal;
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
public class VwapStrategy implements TradingStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(VwapStrategy.class);
  private static final String NAME = "VWAP";
  static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

  // Configuration parameters
  private volatile int targetQuantity;
  private Side side = Side.BUY;
  private LocalTime startTime = LocalTime.of(9, 30);
  private LocalTime endTime = LocalTime.of(16, 0);
  private List<TimeBin> volumeProfile = List.of();

  // Computed allocations: binIndex -> share count
  private final List<Integer> binAllocations = new ArrayList<>();

  // Execution state: tracks which bins have emitted signals
  private final ConcurrentHashMap<Integer, Boolean> binExecuted = new ConcurrentHashMap<>();
  private volatile MarketTick latestTick;
  private volatile boolean completed;

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
    if (completed || latestTick == null || volumeProfile.isEmpty() || targetQuantity <= 0) {
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

    // Find current bin
    int binIndex = findBinIndex(marketTime);
    if (binIndex < 0) {
      return Optional.empty();
    }

    // Check if current bin already executed (at-most-once)
    if (binExecuted.putIfAbsent(binIndex, Boolean.TRUE) != null) {
      return Optional.empty();
    }

    int qty = binAllocations.get(binIndex);
    if (qty <= 0) {
      return Optional.empty();
    }

    var limitPrice = resolveLimitPrice();
    LOG.info("VWAP bin {} emitting {} {} {} shares at {}", binIndex, side, symbol, qty, limitPrice);

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
        "volumeProfile", volumeProfile,
        "executedBins", binExecuted.size(),
        "totalBins", volumeProfile.size());
  }

  @Override
  @SuppressWarnings("unchecked")
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
    if (params.containsKey("volumeProfile")) {
      var raw = params.get("volumeProfile");
      if (raw instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof TimeBin) {
        this.volumeProfile = (List<TimeBin>) list;
      } else if (raw instanceof List<?> list) {
        this.volumeProfile =
            list.stream()
                .filter(item -> item instanceof Map)
                .map(
                    item -> {
                      @SuppressWarnings("unchecked")
                      var m = (Map<String, Object>) item;
                      return new TimeBin(
                          LocalTime.parse((String) m.get("startTime")),
                          LocalTime.parse((String) m.get("endTime")),
                          ((Number) m.get("volumeFraction")).doubleValue());
                    })
                .toList();
      }
    }
    computeAllocations();
    resetExecutionState();
  }

  /**
   * Computes per-bin share allocations from the volume profile. Last bin absorbs rounding
   * remainder.
   */
  private void computeAllocations() {
    binAllocations.clear();
    if (volumeProfile.isEmpty() || targetQuantity <= 0) {
      return;
    }
    int allocated = 0;
    for (int i = 0; i < volumeProfile.size() - 1; i++) {
      int qty = (int) Math.round(targetQuantity * volumeProfile.get(i).volumeFraction());
      binAllocations.add(qty);
      allocated += qty;
    }
    binAllocations.add(targetQuantity - allocated);
  }

  /** Resets execution state for a fresh run. */
  private void resetExecutionState() {
    binExecuted.clear();
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
    for (var entry : binExecuted.entrySet()) {
      int idx = entry.getKey();
      if (idx >= 0 && idx < binAllocations.size()) {
        executed += binAllocations.get(idx);
      }
    }
    return targetQuantity - executed;
  }

  /** Finds the bin index for the given market time. Returns -1 if not in any bin. */
  private int findBinIndex(LocalTime marketTime) {
    for (int i = 0; i < volumeProfile.size(); i++) {
      var bin = volumeProfile.get(i);
      if (!marketTime.isBefore(bin.startTime()) && marketTime.isBefore(bin.endTime())) {
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
    LOG.info("VWAP sweep: {} {} {} shares at MARKET", side, symbol, remaining);
    return Optional.of(
        new OrderSignal(
            symbol, side, remaining, OrderType.MARKET, null, NAME, latestTick.timestamp()));
  }
}
