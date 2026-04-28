package com.mariaalpha.posttrade.service;

import com.mariaalpha.posttrade.entity.ArrivalSnapshotEntity;
import com.mariaalpha.posttrade.entity.TcaResultEntity;
import com.mariaalpha.posttrade.metrics.PostTradeMetrics;
import com.mariaalpha.posttrade.model.FillRecord;
import com.mariaalpha.posttrade.model.MarketTickEvent;
import com.mariaalpha.posttrade.model.OrderDetails;
import com.mariaalpha.posttrade.model.OrderStatus;
import com.mariaalpha.posttrade.publisher.TcaResultPublisher;
import com.mariaalpha.posttrade.repository.TcaResultRepository;
import com.mariaalpha.posttrade.tca.MarketDataCache;
import com.mariaalpha.posttrade.tca.TcaCalculator;
import com.mariaalpha.posttrade.tca.TcaComputation;
import com.mariaalpha.posttrade.tca.TcaInputs;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TcaService {

  private static final Logger LOG = LoggerFactory.getLogger(TcaService.class);
  private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

  private final TcaResultRepository repository;
  private final ArrivalSnapshotService arrivalSnapshotService;
  private final OrderManagerClient orderManagerClient;
  private final MarketDataCache marketDataCache;
  private final TcaResultPublisher publisher;
  private final PostTradeMetrics metrics;

  public TcaService(
      TcaResultRepository repository,
      ArrivalSnapshotService arrivalSnapshotService,
      OrderManagerClient orderManagerClient,
      MarketDataCache marketDataCache,
      TcaResultPublisher publisher,
      PostTradeMetrics metrics) {
    this.repository = repository;
    this.arrivalSnapshotService = arrivalSnapshotService;
    this.orderManagerClient = orderManagerClient;
    this.marketDataCache = marketDataCache;
    this.publisher = publisher;
    this.metrics = metrics;
  }

  @Transactional
  public Optional<TcaResultEntity> computeForCompletedOrder(UUID orderId) {
    if (repository.existsByOrderId(orderId)) {
      LOG.debug("TCA already exists for order {}, skipping", orderId);
      return repository.findByOrderId(orderId);
    }

    long t0 = System.nanoTime();

    Optional<OrderDetails> orderOpt = orderManagerClient.fetchOrder(orderId);
    if (orderOpt.isEmpty()) {
      LOG.warn("Skipping TCA: order-manager did not return details for {}", orderId);
      return Optional.empty();
    }
    OrderDetails order = orderOpt.get();
    if (order.status() != OrderStatus.FILLED) {
      LOG.info("Skipping TCA: order {} is not FILLED (status={})", orderId, order.status());
      return Optional.empty();
    }
    Optional<ArrivalSnapshotEntity> arrivalOpt = arrivalSnapshotService.findByOrderId(orderId);
    if (arrivalOpt.isEmpty()) {
      LOG.warn("Skipping TCA: no arrival snapshot for order {}", orderId);
      return Optional.empty();
    }
    ArrivalSnapshotEntity arrival = arrivalOpt.get();

    List<FillRecord> fills = order.safeFills();
    if (fills.isEmpty() || order.avgFillPrice() == null) {
      LOG.warn("Skipping TCA: no fills or avgFillPrice for order {}", orderId);
      return Optional.empty();
    }

    BigDecimal commissionTotal = totalCommission(fills);
    Instant firstFillTs =
        fills.stream().map(FillRecord::filledAt).min(Instant::compareTo).orElse(order.createdAt());
    Instant lastFillTs =
        fills.stream().map(FillRecord::filledAt).max(Instant::compareTo).orElse(order.updatedAt());
    Instant windowStart = order.createdAt() != null ? order.createdAt() : firstFillTs;
    Instant windowEnd = lastFillTs != null ? lastFillTs : order.updatedAt();

    BigDecimal vwapBench = computeIntervalVwap(order.symbol(), windowStart, windowEnd);

    TcaInputs inputs =
        new TcaInputs(
            orderId,
            order.symbol(),
            order.strategy(),
            order.side(),
            order.filledQuantity(),
            arrival.getArrivalMidPrice(),
            arrival.getArrivalBidPrice(),
            arrival.getArrivalAskPrice(),
            order.avgFillPrice(),
            commissionTotal,
            vwapBench,
            windowStart,
            windowEnd);

    TcaComputation result = TcaCalculator.compute(inputs);

    TcaResultEntity entity = toEntity(inputs, result, executionDurationMs(windowStart, windowEnd));
    TcaResultEntity saved = repository.save(entity);
    publisher.publish(saved);
    metrics.recordTcaComputation(
        order.strategy(), result, Duration.ofNanos(System.nanoTime() - t0));
    LOG.info(
        "Computed TCA for order {} ({} {}): slippage={} bps, IS={} bps, vwap={} bps, spread={} bps",
        orderId,
        order.side(),
        order.symbol(),
        result.slippageBps(),
        result.implShortfallBps(),
        result.vwapBenchmarkBps(),
        result.spreadCostBps());
    return Optional.of(saved);
  }

  private static BigDecimal totalCommission(List<FillRecord> fills) {
    BigDecimal sum = BigDecimal.ZERO;
    for (FillRecord f : fills) {
      if (f.commission() != null) {
        sum = sum.add(f.commission());
      }
    }
    return sum;
  }

  private BigDecimal computeIntervalVwap(String symbol, Instant start, Instant end) {
    if (symbol == null || start == null || end == null) {
      return null;
    }
    List<MarketTickEvent> trades = marketDataCache.tradesInRange(symbol, start, end);
    if (trades.isEmpty()) {
      return null;
    }
    BigDecimal notional = BigDecimal.ZERO;
    BigDecimal totalVolume = BigDecimal.ZERO;
    for (MarketTickEvent tick : trades) {
      BigDecimal size = BigDecimal.valueOf(tick.size());
      notional = notional.add(tick.price().multiply(size, MC), MC);
      totalVolume = totalVolume.add(size);
    }
    if (totalVolume.signum() == 0) {
      return null;
    }
    return notional.divide(totalVolume, MC);
  }

  private static Long executionDurationMs(Instant start, Instant end) {
    if (start == null || end == null) {
      return null;
    }
    return Duration.between(start, end).toMillis();
  }

  private static TcaResultEntity toEntity(TcaInputs in, TcaComputation r, Long durationMs) {
    TcaResultEntity e = new TcaResultEntity();
    e.setOrderId(in.orderId());
    e.setSymbol(in.symbol());
    e.setStrategy(in.strategy());
    e.setSide(in.side());
    e.setQuantity(in.quantity());
    e.setSlippageBps(r.slippageBps());
    e.setImplShortfallBps(r.implShortfallBps());
    e.setVwapBenchmarkBps(r.vwapBenchmarkBps());
    e.setSpreadCostBps(r.spreadCostBps());
    e.setArrivalPrice(in.arrivalMidPrice());
    e.setArrivalBidPrice(in.arrivalBidPrice());
    e.setArrivalAskPrice(in.arrivalAskPrice());
    e.setRealizedAvgPrice(in.realizedAvgPrice());
    e.setVwapBenchmarkPrice(in.vwapBenchmarkPrice());
    e.setCommissionTotal(in.commissionTotal());
    e.setExecutionDurationMs(durationMs);
    return e;
  }
}
