package com.mariaalpha.ordermanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mariaalpha.ordermanager.config.PortfolioConfig;
import com.mariaalpha.ordermanager.entity.FillEntity;
import com.mariaalpha.ordermanager.entity.OrderEntity;
import com.mariaalpha.ordermanager.entity.PositionEntity;
import com.mariaalpha.ordermanager.model.Side;
import com.mariaalpha.ordermanager.repository.FillRepository;
import com.mariaalpha.ordermanager.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

  @Mock private PositionRepository positionRepository;
  @Mock private FillRepository fillRepository;

  private PortfolioService service;

  @BeforeEach
  void setUp() {
    var config = new PortfolioConfig(BigDecimal.valueOf(1_000_000));
    service = new PortfolioService(positionRepository, fillRepository, config);
  }

  @Test
  void summaryWithNoPositionsReturnsInitialCash() {
    when(positionRepository.findAll()).thenReturn(List.of());
    when(fillRepository.findAll()).thenReturn(List.of());

    var summary = service.summary();
    assertThat(summary.cashBalance()).isEqualByComparingTo("1000000");
    assertThat(summary.totalValue()).isEqualByComparingTo("1000000");
    assertThat(summary.openPositions()).isEqualTo(0);
    assertThat(summary.realizedPnl()).isEqualByComparingTo("0");
    assertThat(summary.unrealizedPnl()).isEqualByComparingTo("0");
  }

  @Test
  void summaryReflectsBuyFillReducingCash() {
    var order = new OrderEntity();
    order.setOrderId(UUID.randomUUID());
    var buy = newFill(order, "AAPL", Side.BUY, BigDecimal.valueOf(150), BigDecimal.valueOf(100));
    when(positionRepository.findAll()).thenReturn(List.of());
    when(fillRepository.findAll()).thenReturn(List.of(buy));

    var summary = service.summary();
    assertThat(summary.cashBalance()).isEqualByComparingTo("985000");
  }

  @Test
  void summaryReflectsSellFillIncreasingCash() {
    var order = new OrderEntity();
    order.setOrderId(UUID.randomUUID());
    var sell = newFill(order, "AAPL", Side.SELL, BigDecimal.valueOf(150), BigDecimal.valueOf(100));
    when(positionRepository.findAll()).thenReturn(List.of());
    when(fillRepository.findAll()).thenReturn(List.of(sell));

    var summary = service.summary();
    assertThat(summary.cashBalance()).isEqualByComparingTo("1015000");
  }

  @Test
  void summarySubtractsCommissionFromCash() {
    var order = new OrderEntity();
    order.setOrderId(UUID.randomUUID());
    var buy = newFill(order, "AAPL", Side.BUY, BigDecimal.valueOf(100), BigDecimal.valueOf(10));
    buy.setCommission(BigDecimal.valueOf(1));
    when(positionRepository.findAll()).thenReturn(List.of());
    when(fillRepository.findAll()).thenReturn(List.of(buy));

    var summary = service.summary();
    assertThat(summary.cashBalance()).isEqualByComparingTo("998999");
  }

  @Test
  void summaryAggregatesExposureFromOpenPositions() {
    var longPos = newPosition("AAPL", "100", "150", "160", "0", "1000");
    var shortPos = newPosition("TSLA", "-50", "200", "190", "0", "500");
    when(positionRepository.findAll()).thenReturn(List.of(longPos, shortPos));
    when(fillRepository.findAll()).thenReturn(List.of());

    var summary = service.summary();
    assertThat(summary.grossExposure()).isEqualByComparingTo("25500");
    assertThat(summary.netExposure()).isEqualByComparingTo("6500");
    assertThat(summary.openPositions()).isEqualTo(2);
    assertThat(summary.unrealizedPnl()).isEqualByComparingTo("1500");
  }

  @Test
  void summaryExcludesFlatPositionsFromExposure() {
    var flat = newPosition("GOOG", "0", "0", null, "200", "0");
    when(positionRepository.findAll()).thenReturn(List.of(flat));
    when(fillRepository.findAll()).thenReturn(List.of());

    var summary = service.summary();
    assertThat(summary.openPositions()).isEqualTo(0);
    assertThat(summary.grossExposure()).isEqualByComparingTo("0");
    assertThat(summary.realizedPnl()).isEqualByComparingTo("200");
  }

  @Test
  void totalPnlSumsRealizedAndUnrealizedAcrossPositions() {
    var a = newPosition("AAPL", "100", "150", "160", "500", "1000");
    var b = newPosition("MSFT", "50", "300", "290", "-100", "-500");
    when(positionRepository.findAll()).thenReturn(List.of(a, b));

    assertThat(service.totalPnl()).isEqualByComparingTo("900");
  }

  @Test
  void grossExposureUsesAvgEntryWhenNoMark() {
    var p = newPosition("AAPL", "100", "150", null, "0", "0");
    when(positionRepository.findAllOpen()).thenReturn(List.of(p));

    assertThat(service.grossExposure()).isEqualByComparingTo("15000");
  }

  @Test
  void computeCashBalanceIsInitialWhenNoFills() {
    var cash = service.computeCashBalance(BigDecimal.valueOf(500_000), List.of());
    assertThat(cash).isEqualByComparingTo("500000");
  }

  private FillEntity newFill(
      OrderEntity order, String symbol, Side side, BigDecimal price, BigDecimal qty) {
    var fill = new FillEntity();
    fill.setFillId(UUID.randomUUID());
    fill.setOrder(order);
    fill.setSymbol(symbol);
    fill.setSide(side);
    fill.setFillPrice(price);
    fill.setFillQuantity(qty);
    fill.setCommission(BigDecimal.ZERO);
    fill.setFilledAt(Instant.now());
    return fill;
  }

  private PositionEntity newPosition(
      String symbol, String qty, String avg, String mark, String realized, String unrealized) {
    var p = new PositionEntity(symbol);
    p.setNetQuantity(new BigDecimal(qty));
    p.setAvgEntryPrice(new BigDecimal(avg));
    p.setLastMarkPrice(mark != null ? new BigDecimal(mark) : null);
    p.setRealizedPnl(new BigDecimal(realized));
    p.setUnrealizedPnl(new BigDecimal(unrealized));
    return p;
  }
}
