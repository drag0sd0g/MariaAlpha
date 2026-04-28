package com.mariaalpha.posttrade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.posttrade.config.TcaConfig;
import com.mariaalpha.posttrade.entity.ArrivalSnapshotEntity;
import com.mariaalpha.posttrade.entity.TcaResultEntity;
import com.mariaalpha.posttrade.metrics.PostTradeMetrics;
import com.mariaalpha.posttrade.model.DataSource;
import com.mariaalpha.posttrade.model.EventType;
import com.mariaalpha.posttrade.model.FillRecord;
import com.mariaalpha.posttrade.model.MarketTickEvent;
import com.mariaalpha.posttrade.model.OrderDetails;
import com.mariaalpha.posttrade.model.OrderStatus;
import com.mariaalpha.posttrade.model.OrderType;
import com.mariaalpha.posttrade.model.Side;
import com.mariaalpha.posttrade.publisher.TcaResultPublisher;
import com.mariaalpha.posttrade.repository.TcaResultRepository;
import com.mariaalpha.posttrade.tca.MarketDataCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TcaServiceTest {

  private TcaResultRepository repository;
  private ArrivalSnapshotService arrivalSnapshotService;
  private OrderManagerClient orderManagerClient;
  private MarketDataCache cache;
  private TcaResultPublisher publisher;
  private PostTradeMetrics metrics;
  private TcaService service;

  @BeforeEach
  void setUp() {
    repository = mock(TcaResultRepository.class);
    arrivalSnapshotService = mock(ArrivalSnapshotService.class);
    orderManagerClient = mock(OrderManagerClient.class);
    cache = new MarketDataCache(new TcaConfig(21600, 1000, 60, "http://localhost", 2000));
    publisher = mock(TcaResultPublisher.class);
    metrics = new PostTradeMetrics(new SimpleMeterRegistry());
    service =
        new TcaService(
            repository, arrivalSnapshotService, orderManagerClient, cache, publisher, metrics);
  }

  @Test
  void computesAndPublishesTcaForFilledOrder() {
    UUID orderId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-04-20T09:30:00Z");
    Instant filledAt = Instant.parse("2026-04-20T09:40:00Z");
    when(repository.existsByOrderId(orderId)).thenReturn(false);
    when(arrivalSnapshotService.findByOrderId(orderId))
        .thenReturn(Optional.of(arrival(orderId, createdAt)));
    when(orderManagerClient.fetchOrder(orderId))
        .thenReturn(Optional.of(details(orderId, createdAt, filledAt)));
    cache.record(trade("AAPL", Instant.parse("2026-04-20T09:32:00Z"), 180.02, 1000));
    cache.record(trade("AAPL", Instant.parse("2026-04-20T09:35:00Z"), 180.04, 1000));
    when(repository.save(any(TcaResultEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    Optional<TcaResultEntity> saved = service.computeForCompletedOrder(orderId);

    assertThat(saved).isPresent();
    TcaResultEntity e = saved.get();
    assertThat(e.getOrderId()).isEqualTo(orderId);
    assertThat(e.getSide()).isEqualTo(Side.BUY);
    assertThat(e.getSlippageBps()).isNotNull();
    assertThat(e.getImplShortfallBps()).isNotNull();
    assertThat(e.getVwapBenchmarkBps()).isNotNull();
    assertThat(e.getSpreadCostBps()).isNotNull();
    assertThat(e.getVwapBenchmarkPrice()).isEqualByComparingTo(new BigDecimal("180.03"));
    assertThat(e.getCommissionTotal()).isEqualByComparingTo(new BigDecimal("10"));
    verify(publisher).publish(e);
  }

  @Test
  void skipsWhenTcaAlreadyComputed() {
    UUID orderId = UUID.randomUUID();
    when(repository.existsByOrderId(orderId)).thenReturn(true);
    TcaResultEntity existing = new TcaResultEntity();
    existing.setOrderId(orderId);
    when(repository.findByOrderId(orderId)).thenReturn(Optional.of(existing));

    Optional<TcaResultEntity> result = service.computeForCompletedOrder(orderId);

    assertThat(result).containsSame(existing);
    verify(publisher, never()).publish(any());
  }

  @Test
  void skipsWhenOrderNotFound() {
    UUID orderId = UUID.randomUUID();
    when(repository.existsByOrderId(orderId)).thenReturn(false);
    when(orderManagerClient.fetchOrder(orderId)).thenReturn(Optional.empty());

    assertThat(service.computeForCompletedOrder(orderId)).isEmpty();
    verify(publisher, never()).publish(any());
  }

  @Test
  void skipsWhenOrderNotFilled() {
    UUID orderId = UUID.randomUUID();
    when(repository.existsByOrderId(orderId)).thenReturn(false);
    when(orderManagerClient.fetchOrder(orderId))
        .thenReturn(
            Optional.of(
                new OrderDetails(
                    orderId,
                    "c-1",
                    "AAPL",
                    Side.BUY,
                    OrderType.MARKET,
                    BigDecimal.valueOf(1000),
                    null,
                    OrderStatus.PARTIALLY_FILLED,
                    "VWAP",
                    BigDecimal.valueOf(500),
                    BigDecimal.valueOf(180.05),
                    "x",
                    "ALPACA",
                    Instant.now(),
                    Instant.now(),
                    List.of())));

    assertThat(service.computeForCompletedOrder(orderId)).isEmpty();
  }

  @Test
  void skipsWhenArrivalMissing() {
    UUID orderId = UUID.randomUUID();
    when(repository.existsByOrderId(orderId)).thenReturn(false);
    when(orderManagerClient.fetchOrder(orderId))
        .thenReturn(Optional.of(details(orderId, Instant.now(), Instant.now())));
    when(arrivalSnapshotService.findByOrderId(orderId)).thenReturn(Optional.empty());

    assertThat(service.computeForCompletedOrder(orderId)).isEmpty();
  }

  private static ArrivalSnapshotEntity arrival(UUID orderId, Instant ts) {
    ArrivalSnapshotEntity a = new ArrivalSnapshotEntity();
    a.setOrderId(orderId);
    a.setSymbol("AAPL");
    a.setArrivalTs(ts);
    a.setArrivalMidPrice(new BigDecimal("180.00"));
    a.setArrivalBidPrice(new BigDecimal("179.98"));
    a.setArrivalAskPrice(new BigDecimal("180.02"));
    a.setTickTs(ts);
    return a;
  }

  private static OrderDetails details(UUID orderId, Instant createdAt, Instant filledAt) {
    FillRecord f1 =
        new FillRecord(
            UUID.randomUUID(),
            orderId,
            "AAPL",
            Side.BUY,
            new BigDecimal("180.04"),
            new BigDecimal("500"),
            new BigDecimal("5"),
            "ALPACA",
            "e1",
            Instant.parse("2026-04-20T09:35:00Z"));
    FillRecord f2 =
        new FillRecord(
            UUID.randomUUID(),
            orderId,
            "AAPL",
            Side.BUY,
            new BigDecimal("180.06"),
            new BigDecimal("500"),
            new BigDecimal("5"),
            "ALPACA",
            "e2",
            filledAt);
    return new OrderDetails(
        orderId,
        "c-1",
        "AAPL",
        Side.BUY,
        OrderType.MARKET,
        new BigDecimal("1000"),
        null,
        OrderStatus.FILLED,
        "VWAP",
        new BigDecimal("1000"),
        new BigDecimal("180.05"),
        "x",
        "ALPACA",
        createdAt,
        filledAt,
        List.of(f1, f2));
  }

  private static MarketTickEvent trade(String symbol, Instant ts, double price, long size) {
    return new MarketTickEvent(
        symbol,
        ts,
        EventType.TRADE,
        BigDecimal.valueOf(price),
        size,
        null,
        null,
        null,
        null,
        null,
        DataSource.SIMULATED,
        false);
  }
}
