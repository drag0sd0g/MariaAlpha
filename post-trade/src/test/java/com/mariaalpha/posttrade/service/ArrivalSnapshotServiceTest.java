package com.mariaalpha.posttrade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.posttrade.config.TcaConfig;
import com.mariaalpha.posttrade.entity.ArrivalSnapshotEntity;
import com.mariaalpha.posttrade.model.DataSource;
import com.mariaalpha.posttrade.model.EventType;
import com.mariaalpha.posttrade.model.MarketTickEvent;
import com.mariaalpha.posttrade.model.OrderLifecycleEvent;
import com.mariaalpha.posttrade.model.OrderSnapshotEvent;
import com.mariaalpha.posttrade.model.OrderStatus;
import com.mariaalpha.posttrade.model.OrderType;
import com.mariaalpha.posttrade.model.Side;
import com.mariaalpha.posttrade.repository.ArrivalSnapshotRepository;
import com.mariaalpha.posttrade.tca.MarketDataCache;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArrivalSnapshotServiceTest {

  private ArrivalSnapshotRepository repository;
  private MarketDataCache cache;
  private TcaConfig cfg;
  private ArrivalSnapshotService service;

  @BeforeEach
  void setUp() {
    repository = mock(ArrivalSnapshotRepository.class);
    cfg = new TcaConfig(21600, 1000, 60, "http://localhost", 2000);
    cache = new MarketDataCache(cfg);
    service = new ArrivalSnapshotService(repository, cache, cfg);
  }

  @Test
  void capturesArrivalSnapshotFromNearestQuote() {
    UUID orderId = UUID.randomUUID();
    Instant orderTs = Instant.parse("2026-04-20T09:30:00Z");
    cache.record(quote("AAPL", orderTs.minusSeconds(2), 99.98, 100.02));
    when(repository.existsByOrderId(orderId)).thenReturn(false);
    when(repository.save(any(ArrivalSnapshotEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    Optional<ArrivalSnapshotEntity> snap =
        service.captureIfAbsent(lifecycleEvent(orderId, orderTs));

    assertThat(snap).isPresent();
    assertThat(snap.get().getArrivalMidPrice()).isEqualByComparingTo(new BigDecimal("100.00"));
    assertThat(snap.get().getArrivalBidPrice()).isEqualByComparingTo(BigDecimal.valueOf(99.98));
    assertThat(snap.get().getArrivalAskPrice()).isEqualByComparingTo(BigDecimal.valueOf(100.02));
  }

  @Test
  void idempotent_returnsExistingSnapshotWithoutSaving() {
    UUID orderId = UUID.randomUUID();
    ArrivalSnapshotEntity existing = new ArrivalSnapshotEntity();
    existing.setOrderId(orderId);
    when(repository.existsByOrderId(orderId)).thenReturn(true);
    when(repository.findById(orderId)).thenReturn(Optional.of(existing));

    Optional<ArrivalSnapshotEntity> snap =
        service.captureIfAbsent(lifecycleEvent(orderId, Instant.now()));

    assertThat(snap).containsSame(existing);
    verify(repository, never()).save(any());
  }

  @Test
  void returnsEmptyWhenNoTicksInCache() {
    UUID orderId = UUID.randomUUID();
    when(repository.existsByOrderId(orderId)).thenReturn(false);
    Optional<ArrivalSnapshotEntity> snap =
        service.captureIfAbsent(lifecycleEvent(orderId, Instant.now()));
    assertThat(snap).isEmpty();
    verify(repository, never()).save(any());
  }

  @Test
  void returnsEmptyWhenNearestTickIsOlderThanLookbackWindow() {
    UUID orderId = UUID.randomUUID();
    Instant orderTs = Instant.parse("2026-04-20T09:30:00Z");
    cache.record(quote("AAPL", orderTs.minusSeconds(120), 99.98, 100.02));
    when(repository.existsByOrderId(orderId)).thenReturn(false);
    assertThat(service.captureIfAbsent(lifecycleEvent(orderId, orderTs))).isEmpty();
  }

  @Test
  void fallsBackToTradePriceWhenNoQuoteAvailable() {
    UUID orderId = UUID.randomUUID();
    Instant orderTs = Instant.parse("2026-04-20T09:30:00Z");
    MarketTickEvent tradeOnly =
        new MarketTickEvent(
            "AAPL",
            orderTs.minusSeconds(1),
            EventType.TRADE,
            new BigDecimal("100.00"),
            100L,
            null,
            null,
            null,
            null,
            null,
            DataSource.SIMULATED,
            false);
    cache.record(tradeOnly);
    when(repository.existsByOrderId(orderId)).thenReturn(false);
    when(repository.save(any(ArrivalSnapshotEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    Optional<ArrivalSnapshotEntity> snap =
        service.captureIfAbsent(lifecycleEvent(orderId, orderTs));

    assertThat(snap).isPresent();
    assertThat(snap.get().getArrivalMidPrice()).isEqualByComparingTo(new BigDecimal("100.00"));
    assertThat(snap.get().getArrivalBidPrice()).isNull();
    assertThat(snap.get().getArrivalAskPrice()).isNull();
  }

  private static MarketTickEvent quote(String symbol, Instant ts, double bid, double ask) {
    return new MarketTickEvent(
        symbol,
        ts,
        EventType.QUOTE,
        null,
        null,
        BigDecimal.valueOf(bid),
        BigDecimal.valueOf(ask),
        100L,
        100L,
        null,
        DataSource.SIMULATED,
        false);
  }

  private static OrderLifecycleEvent lifecycleEvent(UUID orderId, Instant ts) {
    OrderSnapshotEvent snap =
        new OrderSnapshotEvent(
            orderId.toString(),
            "c-1",
            "AAPL",
            Side.BUY,
            1000,
            OrderType.MARKET,
            null,
            null,
            "VWAP",
            0,
            null,
            "x",
            "ALPACA");
    return new OrderLifecycleEvent(orderId.toString(), OrderStatus.NEW, snap, null, null, ts);
  }
}
