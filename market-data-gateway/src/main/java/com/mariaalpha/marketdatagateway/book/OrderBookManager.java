package com.mariaalpha.marketdatagateway.book;

import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import com.mariaalpha.marketdatagateway.model.MarketTick;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
public class OrderBookManager {

  private static final Logger LOG = LoggerFactory.getLogger(OrderBookManager.class);

  private final MarketDataAdapter adapter;
  private final ConcurrentHashMap<String, OrderBookEntry> books = new ConcurrentHashMap<>();
  private final Sinks.Many<OrderBookEntry> updateSink =
      Sinks.many().multicast().onBackpressureBuffer();
  private volatile Disposable subscription;

  public OrderBookManager(MarketDataAdapter adapter) {
    this.adapter = adapter;
  }

  @PostConstruct
  void start() {
    subscription = adapter.streamTicks().subscribe(this::onTick);
    LOG.info("Order book manager started");
  }

  @PreDestroy
  void stop() {
    if (subscription != null && !subscription.isDisposed()) {
      subscription.dispose();
    }
    LOG.info("Order book manager stopped");
  }

  /** Updates the in-memory book for the tick's symbol. */
  void onTick(MarketTick tick) {
    var updated =
        books.compute(
            tick.symbol(),
            (symbol, current) -> {
              if (current == null) {
                current = OrderBookEntry.empty(symbol);
              }
              return current.update(tick);
            });
    updateSink.tryEmitNext(updated);
  }

  /** Returns the current snapshot for a symbol, or an empty entry if unknown. */
  public OrderBookEntry getSnapshot(String symbol) {
    return books.getOrDefault(symbol, OrderBookEntry.empty(symbol));
  }

  /** Returns a reactive stream of book updates filtered to the requested symbols. */
  public Flux<OrderBookEntry> streamSnapshots(Set<String> symbols) {
    return updateSink.asFlux().filter(entry -> symbols.contains(entry.symbol()));
  }
}
