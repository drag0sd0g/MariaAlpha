package com.mariaalpha.executionengine.service;

import com.mariaalpha.executionengine.model.MarketState;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-symbol last-seen {@link MarketState}, plus a fan-out hook so components that need to react to
 * NBBO ticks (e.g. {@link com.mariaalpha.executionengine.pegged.PeggedCoordinator}) can register
 * without coupling themselves to {@code MarketDataConsumer}.
 *
 * <p>Listener invocation is best-effort: a listener that throws is logged and skipped — one bad
 * subscriber must never derail the tick fan-out to the others.
 */
@Component
public class MarketStateTracker {

  private static final Logger LOG = LoggerFactory.getLogger(MarketStateTracker.class);

  private final ConcurrentHashMap<String, MarketState> states = new ConcurrentHashMap<>();
  private final List<Consumer<MarketState>> listeners = new CopyOnWriteArrayList<>();

  public void update(MarketState state) {
    states.put(state.symbol(), state);
    for (var listener : listeners) {
      try {
        listener.accept(state);
      } catch (RuntimeException e) {
        LOG.warn(
            "Market-state listener {} threw on {}: {}",
            listener.getClass().getSimpleName(),
            state.symbol(),
            e.getMessage());
      }
    }
  }

  public MarketState getMarketState(String symbol) {
    return states.get(symbol);
  }

  /** Subscribe a listener that fires for every {@link #update(MarketState)} call. */
  public void subscribe(Consumer<MarketState> listener) {
    listeners.add(listener);
  }
}
