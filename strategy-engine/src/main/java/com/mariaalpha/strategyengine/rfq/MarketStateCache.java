package com.mariaalpha.strategyengine.rfq;

import com.mariaalpha.strategyengine.model.MarketTick;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-symbol cache of the latest bid/ask/last + a bounded ring of mid-prices used by {@link
 * VolatilityTracker} for realised-volatility estimation. Populated from the same Kafka tick stream
 * the strategy engine already consumes.
 *
 * <p>The cache is intentionally service-local. Reads on the RFQ hot path are sub-microsecond and
 * the data is reconstructable from the next tick — perfect for an in-process map. A future
 * Redis-backed roll-out would replace this with the shared order-book cache.
 */
@Component
public class MarketStateCache {

  private final int volWindowSize;
  private final ConcurrentHashMap<String, State> stateBySymbol = new ConcurrentHashMap<>();

  public MarketStateCache(RfqPricingConfig config) {
    this.volWindowSize = config.volatilityWindowSize();
  }

  public void onTick(MarketTick tick) {
    if (tick == null || tick.symbol() == null) {
      return;
    }
    var state = stateBySymbol.computeIfAbsent(tick.symbol(), s -> new State(volWindowSize));
    state.update(tick);
  }

  public Optional<Snapshot> snapshot(String symbol) {
    var state = stateBySymbol.get(symbol);
    return state == null ? Optional.empty() : Optional.of(state.snapshot(symbol));
  }

  /** Returns an unmodifiable, point-in-time copy of the rolling mid-price history. */
  public Optional<double[]> midHistory(String symbol) {
    var state = stateBySymbol.get(symbol);
    return state == null ? Optional.empty() : Optional.of(state.midHistory());
  }

  public record Snapshot(
      String symbol,
      BigDecimal bidPrice,
      BigDecimal askPrice,
      BigDecimal lastPrice,
      BigDecimal mid,
      Instant updatedAt,
      boolean stale) {}

  private static final class State {
    private final Deque<Double> midRing;
    private final int windowSize;
    private volatile BigDecimal bidPrice = BigDecimal.ZERO;
    private volatile BigDecimal askPrice = BigDecimal.ZERO;
    private volatile BigDecimal lastPrice = BigDecimal.ZERO;
    private volatile BigDecimal mid = BigDecimal.ZERO;
    private volatile Instant updatedAt = Instant.EPOCH;
    private volatile boolean stale = true;
    private final Object lock = new Object();

    State(int windowSize) {
      this.windowSize = windowSize;
      this.midRing = new ArrayDeque<>(windowSize);
    }

    void update(MarketTick tick) {
      synchronized (lock) {
        if (isPositive(tick.bidPrice())) {
          this.bidPrice = tick.bidPrice();
        }
        if (isPositive(tick.askPrice())) {
          this.askPrice = tick.askPrice();
        }
        if (isPositive(tick.price())) {
          this.lastPrice = tick.price();
        }
        BigDecimal computedMid = computeMid();
        if (isPositive(computedMid)) {
          this.mid = computedMid;
          if (midRing.size() == windowSize) {
            midRing.pollFirst();
          }
          midRing.addLast(computedMid.doubleValue());
        }
        this.updatedAt = tick.timestamp();
        this.stale = tick.stale();
      }
    }

    private BigDecimal computeMid() {
      if (isPositive(bidPrice) && isPositive(askPrice)) {
        return bidPrice
            .add(askPrice)
            .divide(BigDecimal.valueOf(2), 6, java.math.RoundingMode.HALF_UP);
      }
      return isPositive(lastPrice) ? lastPrice : mid;
    }

    Snapshot snapshot(String symbol) {
      synchronized (lock) {
        return new Snapshot(symbol, bidPrice, askPrice, lastPrice, mid, updatedAt, stale);
      }
    }

    double[] midHistory() {
      synchronized (lock) {
        double[] out = new double[midRing.size()];
        int i = 0;
        for (var v : midRing) {
          out[i++] = v;
        }
        return out;
      }
    }

    private static boolean isPositive(BigDecimal v) {
      return v != null && v.signum() > 0;
    }
  }
}
