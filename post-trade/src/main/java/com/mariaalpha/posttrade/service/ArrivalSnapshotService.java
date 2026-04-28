package com.mariaalpha.posttrade.service;

import com.mariaalpha.posttrade.config.TcaConfig;
import com.mariaalpha.posttrade.entity.ArrivalSnapshotEntity;
import com.mariaalpha.posttrade.model.MarketTickEvent;
import com.mariaalpha.posttrade.model.OrderLifecycleEvent;
import com.mariaalpha.posttrade.repository.ArrivalSnapshotRepository;
import com.mariaalpha.posttrade.tca.MarketDataCache;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArrivalSnapshotService {

  private static final Logger LOG = LoggerFactory.getLogger(ArrivalSnapshotService.class);
  private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
  private static final BigDecimal TWO = BigDecimal.valueOf(2L);

  private final ArrivalSnapshotRepository repository;
  private final MarketDataCache marketDataCache;
  private final TcaConfig tcaConfig;

  public ArrivalSnapshotService(
      ArrivalSnapshotRepository repository, MarketDataCache marketDataCache, TcaConfig tcaConfig) {
    this.repository = repository;
    this.marketDataCache = marketDataCache;
    this.tcaConfig = tcaConfig;
  }

  @Transactional
  public Optional<ArrivalSnapshotEntity> captureIfAbsent(OrderLifecycleEvent event) {
    if (event == null || event.order() == null || event.orderId() == null) {
      return Optional.empty();
    }
    UUID orderId;
    try {
      orderId = UUID.fromString(event.orderId());
    } catch (IllegalArgumentException e) {
      LOG.warn("Cannot parse orderId '{}' as UUID", event.orderId());
      return Optional.empty();
    }
    if (repository.existsByOrderId(orderId)) {
      return repository.findById(orderId);
    }
    String symbol = event.order().symbol();
    Instant orderTs = event.timestamp();
    if (symbol == null || orderTs == null) {
      LOG.warn("Missing symbol/timestamp on lifecycle event for order {}", orderId);
      return Optional.empty();
    }
    Optional<MarketTickEvent> nearest = marketDataCache.nearestAtOrBefore(symbol, orderTs);
    if (nearest.isEmpty()) {
      LOG.info(
          "No tick in cache at-or-before {} for symbol {}; skipping arrival snapshot for order {}",
          orderTs,
          symbol,
          orderId);
      return Optional.empty();
    }
    MarketTickEvent tick = nearest.get();
    if (Duration.between(tick.timestamp(), orderTs).compareTo(tcaConfig.arrivalLookback()) > 0) {
      LOG.info(
          "Nearest tick at {} is older than max lookback {} for order {}; skipping",
          tick.timestamp(),
          tcaConfig.arrivalLookback(),
          orderId);
      return Optional.empty();
    }
    ArrivalSnapshotEntity entity = new ArrivalSnapshotEntity();
    entity.setOrderId(orderId);
    entity.setSymbol(symbol);
    entity.setArrivalTs(orderTs);
    entity.setTickTs(tick.timestamp());
    BigDecimal mid = deriveMid(tick);
    entity.setArrivalMidPrice(mid);
    entity.setArrivalBidPrice(tick.bidPrice());
    entity.setArrivalAskPrice(tick.askPrice());
    return Optional.of(repository.save(entity));
  }

  @Transactional(readOnly = true)
  public Optional<ArrivalSnapshotEntity> findByOrderId(UUID orderId) {
    return repository.findById(orderId);
  }

  static BigDecimal deriveMid(MarketTickEvent tick) {
    if (tick.bidPrice() != null
        && tick.askPrice() != null
        && tick.bidPrice().signum() > 0
        && tick.askPrice().signum() > 0) {
      return tick.bidPrice().add(tick.askPrice()).divide(TWO, MC);
    }
    return tick.price();
  }
}
