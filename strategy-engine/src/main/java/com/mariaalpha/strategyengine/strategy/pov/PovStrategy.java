package com.mariaalpha.strategyengine.strategy.pov;

import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Percentage-of-Volume (POV) execution strategy.
 *
 * <p>POV participates as a configurable fraction of the real-time traded volume on the tape: as the
 * market trades more shares the strategy emits child orders proportionally, so its participation
 * rate stays close to {@code participationRate} cumulatively across the window.
 *
 * <p>Bookkeeping is purely cumulative — no fixed time slicing. The strategy tracks the running
 * market volume seen since {@code startTime} (summing {@code size} from {@link EventType#TRADE}
 * ticks only; quotes carry no executed volume) and, on each tick, computes the desired
 * already-traded total as {@code targetExecuted = floor(participationRate × cumulativeVolume)},
 * capped by the parent {@code targetQuantity}. The delta vs. shares already emitted is the next
 * child clip. Clips smaller than {@code minClipSize} are deferred until volume builds; clips larger
 * than {@code maxClipSize} are capped so a single block print can't drag a parent off course.
 *
 * <p>Child orders are LIMIT at the best ask (BUY) / best bid (SELL) for price protection, mirroring
 * the other slicing strategies. Any quantity still outstanding when the window closes is swept with
 * a single MARKET order at {@code endTime}.
 *
 * <p>POV is the reactive counterpart to TWAP/VWAP/IS: those algorithms commit to a schedule up
 * front; POV adapts to whatever the tape actually does. Trade-off: lower information leakage and
 * better impact on slow days, but no completion guarantee within the window if liquidity dries up —
 * the end-of-window MARKET sweep is the safety valve.
 */
@Component
public class PovStrategy implements TradingStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(PovStrategy.class);
  private static final String NAME = "POV";
  static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
  private static final double DEFAULT_PARTICIPATION_RATE = 0.10;
  private static final int DEFAULT_MIN_CLIP_SIZE = 1;
  private static final int DEFAULT_MAX_CLIP_SIZE = Integer.MAX_VALUE;

  // Configuration parameters
  private volatile int targetQuantity;
  private volatile Side side = Side.BUY;
  private volatile LocalTime startTime = LocalTime.of(9, 30);
  private volatile LocalTime endTime = LocalTime.of(16, 0);
  private volatile double participationRate = DEFAULT_PARTICIPATION_RATE;
  private volatile int minClipSize = DEFAULT_MIN_CLIP_SIZE;
  private volatile int maxClipSize = DEFAULT_MAX_CLIP_SIZE;

  // Execution state — guarded by the strategy's intrinsic lock for atomic onTick/evaluate updates
  private long cumulativeMarketVolume;
  private long emittedQuantity;
  private volatile MarketTick latestTick;
  private volatile boolean completed;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public synchronized void onTick(MarketTick tick) {
    this.latestTick = tick;
    if (tick.eventType() != EventType.TRADE || tick.size() <= 0) {
      return;
    }
    var marketTime = tick.timestamp().atZone(MARKET_ZONE).toLocalTime();
    if (marketTime.isBefore(startTime) || !marketTime.isBefore(endTime)) {
      return;
    }
    cumulativeMarketVolume += tick.size();
  }

  @Override
  public synchronized Optional<OrderSignal> evaluate(String symbol) {
    if (completed || latestTick == null || targetQuantity <= 0 || participationRate <= 0.0) {
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

    long targetExecuted =
        Math.min((long) targetQuantity, (long) (participationRate * cumulativeMarketVolume));
    long delta = targetExecuted - emittedQuantity;

    if (delta < minClipSize) {
      return Optional.empty();
    }

    int clipQty =
        (int) Math.min(delta, Math.min(maxClipSize, (long) targetQuantity - emittedQuantity));
    if (clipQty <= 0) {
      return Optional.empty();
    }

    emittedQuantity += clipQty;

    var limitPrice = resolveLimitPrice();
    LOG.info(
        "POV emitting {} {} {} shares at {} (participation={}, cumVolume={}, emitted={}/{})",
        side,
        symbol,
        clipQty,
        limitPrice,
        participationRate,
        cumulativeMarketVolume,
        emittedQuantity,
        targetQuantity);

    return Optional.of(
        new OrderSignal(
            symbol, side, clipQty, OrderType.LIMIT, limitPrice, NAME, latestTick.timestamp()));
  }

  @Override
  public synchronized Map<String, Object> getParameters() {
    return Map.ofEntries(
        Map.entry("targetQuantity", targetQuantity),
        Map.entry("side", side.name()),
        Map.entry("startTime", startTime.toString()),
        Map.entry("endTime", endTime.toString()),
        Map.entry("participationRate", participationRate),
        Map.entry("minClipSize", minClipSize),
        Map.entry("maxClipSize", maxClipSize),
        Map.entry("cumulativeMarketVolume", cumulativeMarketVolume),
        Map.entry("emittedQuantity", emittedQuantity));
  }

  @Override
  public synchronized void updateParameters(Map<String, Object> params) {
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
    if (params.containsKey("participationRate")) {
      this.participationRate = ((Number) params.get("participationRate")).doubleValue();
    }
    if (params.containsKey("minClipSize")) {
      this.minClipSize = ((Number) params.get("minClipSize")).intValue();
    }
    if (params.containsKey("maxClipSize")) {
      this.maxClipSize = ((Number) params.get("maxClipSize")).intValue();
    }
    resetExecutionState();
  }

  /** Resets execution state for a fresh run. */
  private void resetExecutionState() {
    cumulativeMarketVolume = 0L;
    emittedQuantity = 0L;
    latestTick = null;
    completed = false;
  }

  /** Resolves the limit price from the latest tick based on side. */
  private BigDecimal resolveLimitPrice() {
    if (side == Side.BUY) {
      var ask = latestTick.askPrice();
      return ask != null && ask.compareTo(BigDecimal.ZERO) > 0 ? ask : latestTick.price();
    }
    var bid = latestTick.bidPrice();
    return bid != null && bid.compareTo(BigDecimal.ZERO) > 0 ? bid : latestTick.price();
  }

  /** Emits a MARKET sweep for any remaining unexecuted quantity. */
  private Optional<OrderSignal> emitSweep(String symbol) {
    if (completed) {
      return Optional.empty();
    }
    long remaining = (long) targetQuantity - emittedQuantity;
    completed = true;
    if (remaining <= 0) {
      return Optional.empty();
    }
    emittedQuantity += remaining;
    LOG.info("POV sweep: {} {} {} shares at MARKET", side, symbol, remaining);
    return Optional.of(
        new OrderSignal(
            symbol, side, (int) remaining, OrderType.MARKET, null, NAME, latestTick.timestamp()));
  }
}
