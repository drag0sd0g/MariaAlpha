package com.mariaalpha.ordermanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.mariaalpha.ordermanager.entity.FillEntity;
import com.mariaalpha.ordermanager.entity.OrderEntity;
import com.mariaalpha.ordermanager.entity.PositionEntity;
import com.mariaalpha.ordermanager.model.Side;
import com.mariaalpha.ordermanager.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PositionServiceTest {

  @Mock private PositionRepository positionRepository;
  private PositionService service;
  private final Map<String, PositionEntity> store = new HashMap<>();

  @BeforeEach
  void setUp() {
    store.clear();
    service = new PositionService(positionRepository);
    when(positionRepository.findForUpdate(anyString()))
        .thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
    when(positionRepository.save(any(PositionEntity.class)))
        .thenAnswer(
            inv -> {
              PositionEntity p = inv.getArgument(0);
              store.put(p.getSymbol(), p);
              return p;
            });
  }

  @Test
  void openingLongPositionSetsAvgEntryPrice() {
    var fill = newFill("AAPL", Side.BUY, BigDecimal.valueOf(150), BigDecimal.valueOf(100));
    var result = service.applyFill(fill);

    assertThat(result.getNetQuantity()).isEqualByComparingTo("100");
    assertThat(result.getAvgEntryPrice()).isEqualByComparingTo("150");
    assertThat(result.getRealizedPnl()).isEqualByComparingTo("0");
  }

  @Test
  void openingShortPositionYieldsNegativeQty() {
    var fill = newFill("TSLA", Side.SELL, BigDecimal.valueOf(200), BigDecimal.valueOf(50));
    var result = service.applyFill(fill);

    assertThat(result.getNetQuantity()).isEqualByComparingTo("-50");
    assertThat(result.getAvgEntryPrice()).isEqualByComparingTo("200");
  }

  @Test
  void addingToLongPositionWeightsAvgEntry() {
    service.applyFill(newFill("AAPL", Side.BUY, BigDecimal.valueOf(100), BigDecimal.valueOf(50)));
    var result =
        service.applyFill(
            newFill("AAPL", Side.BUY, BigDecimal.valueOf(120), BigDecimal.valueOf(50)));

    assertThat(result.getNetQuantity()).isEqualByComparingTo("100");
    assertThat(result.getAvgEntryPrice()).isEqualByComparingTo("110");
    assertThat(result.getRealizedPnl()).isEqualByComparingTo("0");
  }

  @Test
  void partialCloseRealizesProportionalPnl() {
    service.applyFill(newFill("AAPL", Side.BUY, BigDecimal.valueOf(100), BigDecimal.valueOf(100)));
    var result =
        service.applyFill(
            newFill("AAPL", Side.SELL, BigDecimal.valueOf(120), BigDecimal.valueOf(40)));

    assertThat(result.getNetQuantity()).isEqualByComparingTo("60");
    assertThat(result.getAvgEntryPrice()).isEqualByComparingTo("100");
    assertThat(result.getRealizedPnl()).isEqualByComparingTo("800");
  }

  @Test
  void fullCloseGoesFlatAndRealizesAllPnl() {
    service.applyFill(newFill("AAPL", Side.BUY, BigDecimal.valueOf(100), BigDecimal.valueOf(50)));
    var result =
        service.applyFill(
            newFill("AAPL", Side.SELL, BigDecimal.valueOf(110), BigDecimal.valueOf(50)));

    assertThat(result.getNetQuantity()).isEqualByComparingTo("0");
    assertThat(result.getAvgEntryPrice()).isEqualByComparingTo("0");
    assertThat(result.getRealizedPnl()).isEqualByComparingTo("500");
  }

  @Test
  void flipFromLongToShortResetsAvgEntry() {
    service.applyFill(newFill("AAPL", Side.BUY, BigDecimal.valueOf(100), BigDecimal.valueOf(50)));
    var result =
        service.applyFill(
            newFill("AAPL", Side.SELL, BigDecimal.valueOf(120), BigDecimal.valueOf(80)));

    assertThat(result.getNetQuantity()).isEqualByComparingTo("-30");
    assertThat(result.getAvgEntryPrice()).isEqualByComparingTo("120");
    assertThat(result.getRealizedPnl()).isEqualByComparingTo("1000");
  }

  @Test
  void shortCoverProducesLossIfCoveredHigher() {
    service.applyFill(newFill("TSLA", Side.SELL, BigDecimal.valueOf(200), BigDecimal.valueOf(50)));
    var result =
        service.applyFill(
            newFill("TSLA", Side.BUY, BigDecimal.valueOf(220), BigDecimal.valueOf(50)));

    assertThat(result.getNetQuantity()).isEqualByComparingTo("0");
    assertThat(result.getRealizedPnl()).isEqualByComparingTo("-1000");
  }

  @Test
  void commissionSubtractedFromRealizedPnl() {
    var fill = newFill("AAPL", Side.BUY, BigDecimal.valueOf(100), BigDecimal.valueOf(10));
    fill.setCommission(BigDecimal.valueOf(5));
    var result = service.applyFill(fill);

    assertThat(result.getRealizedPnl()).isEqualByComparingTo("-5");
  }

  @Test
  void unrealizedPnlUsesMarkPriceWhenAvailable() {
    service.updateMarkPrice("AAPL", BigDecimal.valueOf(160));
    var result =
        service.applyFill(
            newFill("AAPL", Side.BUY, BigDecimal.valueOf(150), BigDecimal.valueOf(100)));

    assertThat(result.getLastMarkPrice()).isEqualByComparingTo("160");
    assertThat(result.getUnrealizedPnl()).isEqualByComparingTo("1000");
  }

  @Test
  void unrealizedPnlDefaultsToFillPriceWhenNoMark() {
    var result =
        service.applyFill(
            newFill("AAPL", Side.BUY, BigDecimal.valueOf(150), BigDecimal.valueOf(100)));

    assertThat(result.getLastMarkPrice()).isEqualByComparingTo("150");
    assertThat(result.getUnrealizedPnl()).isEqualByComparingTo("0");
  }

  @Test
  void unrealizedPnlForShortInverts() {
    service.updateMarkPrice("TSLA", BigDecimal.valueOf(180));
    var result =
        service.applyFill(
            newFill("TSLA", Side.SELL, BigDecimal.valueOf(200), BigDecimal.valueOf(50)));

    assertThat(result.getUnrealizedPnl()).isEqualByComparingTo("1000");
  }

  @Test
  void markToMarketUpdatesOpenPositionsOnly() {
    service.applyFill(newFill("AAPL", Side.BUY, BigDecimal.valueOf(100), BigDecimal.valueOf(10)));
    when(positionRepository.findAllOpen()).thenReturn(java.util.List.copyOf(store.values()));
    service.updateMarkPrice("AAPL", BigDecimal.valueOf(110));
    service.markToMarket();

    assertThat(store.get("AAPL").getLastMarkPrice()).isEqualByComparingTo("110");
    assertThat(store.get("AAPL").getUnrealizedPnl()).isEqualByComparingTo("100");
  }

  @Test
  void markToMarketSkipsSymbolsWithoutMarkPrice() {
    service.applyFill(newFill("AAPL", Side.BUY, BigDecimal.valueOf(100), BigDecimal.valueOf(10)));
    when(positionRepository.findAllOpen()).thenReturn(java.util.List.copyOf(store.values()));
    service.markToMarket();

    assertThat(store.get("AAPL").getLastMarkPrice()).isEqualByComparingTo("100");
  }

  private FillEntity newFill(String symbol, Side side, BigDecimal price, BigDecimal qty) {
    var order = new OrderEntity();
    order.setOrderId(UUID.randomUUID());
    order.setSymbol(symbol);
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
}
