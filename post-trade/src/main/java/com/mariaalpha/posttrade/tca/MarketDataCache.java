package com.mariaalpha.posttrade.tca;

import com.mariaalpha.posttrade.config.TcaConfig;
import com.mariaalpha.posttrade.model.EventType;
import com.mariaalpha.posttrade.model.MarketTickEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MarketDataCache {

  private static final Logger LOG = LoggerFactory.getLogger(MarketDataCache.class);

  private final ConcurrentHashMap<String, ConcurrentNavigableMap<Instant, MarketTickEvent>>
      perSymbol = new ConcurrentHashMap<>();
  private final TcaConfig tcaConfig;
  private final Clock clock;

  @Autowired
  public MarketDataCache(TcaConfig tcaConfig) {
    this(tcaConfig, Clock.systemUTC());
  }

  MarketDataCache(TcaConfig tcaConfig, Clock clock) {
    this.tcaConfig = tcaConfig;
    this.clock = clock;
  }

  public void record(MarketTickEvent tick) {
    if (tick == null || tick.symbol() == null || tick.timestamp() == null) {
      return;
    }
    var bucket = perSymbol.computeIfAbsent(tick.symbol(), k -> new ConcurrentSkipListMap<>());
    bucket.put(tick.timestamp(), tick);
    enforceSizeLimit(bucket);
  }

  private void enforceSizeLimit(ConcurrentNavigableMap<Instant, MarketTickEvent> bucket) {
    int max = tcaConfig.marketDataCacheMaxTicksPerSymbol();
    while (bucket.size() > max) {
      var eldest = bucket.firstEntry();
      if (eldest == null) {
        return;
      }
      bucket.remove(eldest.getKey());
    }
  }

  public Optional<MarketTickEvent> nearestAtOrBefore(String symbol, Instant t) {
    var bucket = perSymbol.get(symbol);
    if (bucket == null || t == null) {
      return Optional.empty();
    }
    Map.Entry<Instant, MarketTickEvent> entry = bucket.floorEntry(t);
    return Optional.ofNullable(entry).map(Map.Entry::getValue);
  }

  public List<MarketTickEvent> tradesInRange(String symbol, Instant start, Instant end) {
    var bucket = perSymbol.get(symbol);
    if (bucket == null || start == null || end == null || start.isAfter(end)) {
      return List.of();
    }
    var sub = bucket.subMap(start, true, end, true);
    var out = new ArrayList<MarketTickEvent>(sub.size());
    for (var t : sub.values()) {
      if (t.eventType() == EventType.TRADE
          && t.size() != null
          && t.size() > 0L
          && t.price() != null) {
        out.add(t);
      }
    }
    return out;
  }

  public int size(String symbol) {
    var bucket = perSymbol.get(symbol);
    return bucket == null ? 0 : bucket.size();
  }

  @Scheduled(fixedDelayString = "${post-trade.tca.market-data-cache-ttl-seconds:21600}000")
  public void evictStale() {
    Instant cutoff = Instant.now(clock).minus(tcaConfig.cacheTtl());
    int evicted = 0;
    for (var bucket : perSymbol.values()) {
      ConcurrentNavigableMap<Instant, MarketTickEvent> head = bucket.headMap(cutoff, false);
      evicted += head.size();
      head.clear();
    }
    if (evicted > 0) {
      LOG.debug("Evicted {} stale ticks older than {}", evicted, cutoff);
    }
  }
}
