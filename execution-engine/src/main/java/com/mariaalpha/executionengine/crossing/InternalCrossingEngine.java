package com.mariaalpha.executionengine.crossing;

import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("simulated")
public class InternalCrossingEngine {

  private static final Logger LOG = LoggerFactory.getLogger(InternalCrossingEngine.class);
  private static final int PRICE_SCALE = 8;

  private final MarketStateTracker marketStateTracker;
  private final Object lock = new Object();
  private final Map<String, EnumMap<Side, Deque<RestingOrder>>> book = new ConcurrentHashMap<>();
  private final Map<String, RestingOrder> byExchangeId = new ConcurrentHashMap<>();
  private static final int RECENT_RING_SIZE = 256;
  private final List<MidpointCross> recent = new ArrayList<>();

  private final AtomicLong crossesTotal = new AtomicLong(0);
  private final AtomicLong internalCrossesTotal = new AtomicLong(0);
  private final AtomicLong syntheticCrossesTotal = new AtomicLong(0);
  private final AtomicLong sharesCrossedTotal = new AtomicLong(0);
  private final Object spreadLock = new Object();
  private BigDecimal spreadCapturedNotional = BigDecimal.ZERO;

  private final CopyOnWriteArrayList<CrossListener> listeners = new CopyOnWriteArrayList<>();

  public InternalCrossingEngine(MarketStateTracker marketStateTracker) {
    this.marketStateTracker = marketStateTracker;
  }

  public void addCrossListener(CrossListener listener) {
    if (listener != null) {
      listeners.add(listener);
    }
  }

  public void clearCrossListeners() {
    listeners.clear();
  }

  public String submit(Order order) {
    var exchangeId = "INT-" + UUID.randomUUID().toString().substring(0, 8);
    var resting = new RestingOrder(exchangeId, order, Instant.now());
    List<MidpointCross> crosses;
    synchronized (lock) {
      byExchangeId.put(exchangeId, resting);
      var midpoint = midpoint(order.getSymbol());
      crosses = tryCross(resting, midpoint);
      if (resting.remaining() > 0) {
        sideQueue(order.getSymbol(), order.getSide()).addLast(resting);
      } else {
        byExchangeId.remove(exchangeId);
      }
    }
    crosses.forEach(this::publish);
    return exchangeId;
  }

  public boolean cancel(String exchangeOrderId) {
    synchronized (lock) {
      var removed = byExchangeId.remove(exchangeOrderId);
      if (removed == null) {
        return false;
      }
      var queue = sideQueue(removed.symbol(), removed.side());
      queue.removeIf(r -> r.exchangeOrderId().equals(exchangeOrderId));
      return true;
    }
  }

  public Optional<MidpointCross> synthesizeCounterparty(String exchangeOrderId) {
    MidpointCross cross;
    synchronized (lock) {
      var resting = byExchangeId.get(exchangeOrderId);
      if (resting == null || resting.remaining() <= 0) {
        return Optional.empty();
      }
      var midpoint = midpoint(resting.symbol());
      if (midpoint == null || !resting.priceAcceptable(midpoint)) {
        return Optional.empty();
      }
      var qty = resting.remaining();
      cross = buildCross(resting, null, null, midpoint, qty);
      resting.decrementRemaining(qty);
      removeIfFilled(resting);
    }
    publish(cross);
    return Optional.of(cross);
  }

  public List<MidpointCross> sweep() {
    List<MidpointCross> produced;
    synchronized (lock) {
      produced = new ArrayList<>();
      for (var symbol : List.copyOf(book.keySet())) {
        var midpoint = midpoint(symbol);
        if (midpoint == null) {
          continue;
        }
        produced.addAll(matchOpposingSides(symbol, midpoint));
      }
    }
    produced.forEach(this::publish);
    return produced;
  }

  public CrossingStats stats() {
    int restingBuy;
    int restingSell;
    int symbols;
    synchronized (lock) {
      restingBuy =
          book.values().stream()
              .mapToInt(m -> m.getOrDefault(Side.BUY, new ArrayDeque<>()).size())
              .sum();
      restingSell =
          book.values().stream()
              .mapToInt(m -> m.getOrDefault(Side.SELL, new ArrayDeque<>()).size())
              .sum();
      symbols = book.size();
    }
    BigDecimal spread;
    synchronized (spreadLock) {
      spread = spreadCapturedNotional;
    }
    return new CrossingStats(
        crossesTotal.get(),
        internalCrossesTotal.get(),
        syntheticCrossesTotal.get(),
        sharesCrossedTotal.get(),
        spread,
        restingBuy,
        restingSell,
        symbols);
  }

  public List<MidpointCross> recentCrosses() {
    synchronized (recent) {
      var copy = new ArrayList<>(recent);
      java.util.Collections.reverse(copy);
      return List.copyOf(copy);
    }
  }

  public Map<String, BookSide> bookSnapshot() {
    synchronized (lock) {
      var snap = new java.util.LinkedHashMap<String, BookSide>();
      for (var entry : book.entrySet()) {
        var buys = entry.getValue().getOrDefault(Side.BUY, new ArrayDeque<>());
        var sells = entry.getValue().getOrDefault(Side.SELL, new ArrayDeque<>());
        snap.put(
            entry.getKey(),
            new BookSide(sumRemaining(buys), buys.size(), sumRemaining(sells), sells.size()));
      }
      return Map.copyOf(snap);
    }
  }

  public int totalResting() {
    synchronized (lock) {
      return book.values().stream().flatMap(m -> m.values().stream()).mapToInt(Deque::size).sum();
    }
  }

  public boolean isResting(String exchangeOrderId) {
    return byExchangeId.containsKey(exchangeOrderId);
  }

  public int remainingFor(String exchangeOrderId) {
    var resting = byExchangeId.get(exchangeOrderId);
    return resting == null ? 0 : resting.remaining();
  }

  public Optional<String> firstRestingOrderId(String symbol) {
    synchronized (lock) {
      var sides = book.get(symbol);
      if (sides == null) {
        return Optional.empty();
      }
      RestingOrder earliest = null;
      for (var queue : sides.values()) {
        var head = queue.peekFirst();
        if (head == null || head.remaining() <= 0) {
          continue;
        }
        if (earliest == null || head.arrival().isBefore(earliest.arrival())) {
          earliest = head;
        }
      }
      return earliest == null ? Optional.empty() : Optional.of(earliest.exchangeOrderId());
    }
  }

  private List<MidpointCross> tryCross(RestingOrder aggressor, BigDecimal midpoint) {
    var crosses = new ArrayList<MidpointCross>();
    if (midpoint == null || !aggressor.priceAcceptable(midpoint)) {
      return crosses;
    }
    var opposite = sideQueue(aggressor.symbol(), opposite(aggressor.side()));
    Iterator<RestingOrder> iter = opposite.iterator();
    while (aggressor.remaining() > 0 && iter.hasNext()) {
      var counter = iter.next();
      if (!counter.priceAcceptable(midpoint)) {
        continue;
      }
      int matchQty = Math.min(aggressor.remaining(), counter.remaining());
      if (matchQty <= 0) {
        continue;
      }
      var cross = buildCross(aggressor, counter, counter, midpoint, matchQty);
      aggressor.decrementRemaining(matchQty);
      counter.decrementRemaining(matchQty);
      if (counter.remaining() == 0) {
        iter.remove();
        byExchangeId.remove(counter.exchangeOrderId());
      }
      crosses.add(cross);
    }
    return crosses;
  }

  private List<MidpointCross> matchOpposingSides(String symbol, BigDecimal midpoint) {
    var produced = new ArrayList<MidpointCross>();
    var buys = sideQueue(symbol, Side.BUY);
    var sells = sideQueue(symbol, Side.SELL);
    while (true) {
      RestingOrder buy = peekAcceptable(buys, midpoint);
      RestingOrder sell = peekAcceptable(sells, midpoint);
      if (buy == null || sell == null) {
        break;
      }
      int matchQty = Math.min(buy.remaining(), sell.remaining());
      if (matchQty <= 0) {
        break;
      }
      RestingOrder aggressor = buy.arrival().isAfter(sell.arrival()) ? buy : sell;
      RestingOrder counter = aggressor == buy ? sell : buy;
      produced.add(buildCross(aggressor, counter, counter, midpoint, matchQty));
      buy.decrementRemaining(matchQty);
      sell.decrementRemaining(matchQty);
      removeIfFilled(buy);
      removeIfFilled(sell);
    }
    return produced;
  }

  private RestingOrder peekAcceptable(Deque<RestingOrder> queue, BigDecimal midpoint) {
    while (!queue.isEmpty()) {
      var head = queue.peekFirst();
      if (head.remaining() <= 0) {
        queue.pollFirst();
        byExchangeId.remove(head.exchangeOrderId());
        continue;
      }
      if (!head.priceAcceptable(midpoint)) {
        return null;
      }
      return head;
    }
    return null;
  }

  private MidpointCross buildCross(
      RestingOrder aggressor,
      RestingOrder counterRef,
      RestingOrder counterpartyResting,
      BigDecimal midpoint,
      int qty) {
    var market = marketStateTracker.getMarketState(aggressor.symbol());
    var spreadBps =
        market != null && market.bidPrice() != null && market.askPrice() != null
            ? market
                .askPrice()
                .subtract(market.bidPrice())
                .divide(midpoint, PRICE_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(10_000))
            : BigDecimal.ZERO;
    var cross =
        new MidpointCross(
            aggressor.exchangeOrderId(),
            counterRef == null ? null : counterRef.exchangeOrderId(),
            aggressor.symbol(),
            aggressor.side(),
            qty,
            midpoint,
            spreadBps,
            counterpartyResting == null,
            Instant.now());
    return cross;
  }

  private void publish(MidpointCross cross) {
    synchronized (recent) {
      recent.add(cross);
      while (recent.size() > RECENT_RING_SIZE) {
        recent.remove(0);
      }
    }
    crossesTotal.incrementAndGet();
    sharesCrossedTotal.addAndGet(cross.quantity());
    if (cross.synthetic()) {
      syntheticCrossesTotal.incrementAndGet();
    } else {
      internalCrossesTotal.incrementAndGet();
    }
    var captured =
        cross
            .spreadBps()
            .divide(BigDecimal.valueOf(10_000), PRICE_SCALE, RoundingMode.HALF_UP)
            .multiply(cross.midpoint())
            .multiply(BigDecimal.valueOf(cross.quantity()));
    synchronized (spreadLock) {
      spreadCapturedNotional = spreadCapturedNotional.add(captured);
    }
    LOG.info(
        "INTERNAL cross: {} {}@{} midpoint={} spreadBps={} synthetic={} aggressor={} counter={}",
        cross.side(),
        cross.quantity(),
        cross.symbol(),
        cross.midpoint(),
        cross.spreadBps(),
        cross.synthetic(),
        cross.aggressorExchangeOrderId(),
        cross.counterpartyExchangeOrderId());
    for (var listener : listeners) {
      try {
        listener.onCross(cross);
      } catch (RuntimeException e) {
        LOG.warn("Internal-crossing listener threw — ignoring and continuing fan-out", e);
      }
    }
  }

  private void removeIfFilled(RestingOrder resting) {
    if (resting.remaining() > 0) {
      return;
    }
    sideQueue(resting.symbol(), resting.side())
        .removeIf(r -> r.exchangeOrderId().equals(resting.exchangeOrderId()));
    byExchangeId.remove(resting.exchangeOrderId());
  }

  private Deque<RestingOrder> sideQueue(String symbol, Side side) {
    return book.computeIfAbsent(symbol, s -> new EnumMap<>(Side.class))
        .computeIfAbsent(side, s -> new ArrayDeque<>());
  }

  private BigDecimal midpoint(String symbol) {
    MarketState m = marketStateTracker.getMarketState(symbol);
    if (m == null || m.bidPrice() == null || m.askPrice() == null) {
      return null;
    }
    if (m.bidPrice().signum() <= 0 || m.askPrice().compareTo(m.bidPrice()) <= 0) {
      return null;
    }
    return m.bidPrice()
        .add(m.askPrice())
        .divide(BigDecimal.valueOf(2), PRICE_SCALE, RoundingMode.HALF_UP);
  }

  private static Side opposite(Side side) {
    return side == Side.BUY ? Side.SELL : Side.BUY;
  }

  private static int sumRemaining(Collection<RestingOrder> orders) {
    return orders.stream().mapToInt(RestingOrder::remaining).sum();
  }

  public record BookSide(int buyQty, int buyOrders, int sellQty, int sellOrders) {}

  public interface CrossListener {
    void onCross(MidpointCross cross);
  }
}
