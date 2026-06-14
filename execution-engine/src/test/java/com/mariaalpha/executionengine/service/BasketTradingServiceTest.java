package com.mariaalpha.executionengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.mariaalpha.executionengine.basket.BasketMetrics;
import com.mariaalpha.executionengine.basket.BasketRegistry;
import com.mariaalpha.executionengine.basket.BasketStatus;
import com.mariaalpha.executionengine.controller.dto.BasketLegRequest;
import com.mariaalpha.executionengine.controller.dto.BasketOrderRequest;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderStatus;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BasketTradingServiceTest {

  private OrderExecutionService executionService;
  private BasketRegistry registry;
  private BasketTradingService service;

  @BeforeEach
  void setUp() {
    executionService = mock(OrderExecutionService.class);
    registry = new BasketRegistry();
    service =
        new BasketTradingService(
            executionService, registry, new BasketMetrics(new SimpleMeterRegistry()));
  }

  private BasketLegRequest limitLeg(String symbol, Side side, int qty, String price) {
    return new BasketLegRequest(
        symbol, side, OrderType.LIMIT, qty, new BigDecimal(price), null, null);
  }

  @Test
  void submit_allAccepted_returnsSubmittedBasketAndTracksIt() {
    var request =
        new BasketOrderRequest(
            "rebalance",
            List.of(
                limitLeg("AAPL", Side.BUY, 100, "150.00"),
                limitLeg("MSFT", Side.BUY, 50, "300.00")));

    var view = service.submit(request);

    assertThat(view.basketId()).isNotBlank();
    assertThat(view.name()).isEqualTo("rebalance");
    assertThat(view.totalLegs()).isEqualTo(2);
    assertThat(view.acceptedLegs()).isEqualTo(2);
    assertThat(view.status()).isEqualTo(BasketStatus.SUBMITTED);
    assertThat(registry.trackedBaskets()).isEqualTo(1);
    verify(executionService, times(2)).submitOrder(any(Order.class));
  }

  @Test
  void submit_oneLegRejected_reflectsMixedStatus() {
    rejectSymbol("MSFT");
    var request =
        new BasketOrderRequest(
            null,
            List.of(
                limitLeg("AAPL", Side.BUY, 100, "150.00"),
                limitLeg("MSFT", Side.BUY, 50, "300.00")));

    var view = service.submit(request);

    assertThat(view.acceptedLegs()).isEqualTo(1);
    assertThat(view.rejectedLegs()).isEqualTo(1);
    assertThat(view.status()).isEqualTo(BasketStatus.SUBMITTED);
  }

  @Test
  void submit_allLegsRejected_basketRejected() {
    rejectSymbol("AAPL");
    var request = new BasketOrderRequest(null, List.of(limitLeg("AAPL", Side.BUY, 100, "150.00")));

    var view = service.submit(request);

    assertThat(view.status()).isEqualTo(BasketStatus.REJECTED);
    assertThat(view.acceptedLegs()).isZero();
  }

  @Test
  void submit_linksLegsSoFillsAttributeToBasket() {
    var view =
        service.submit(
            new BasketOrderRequest(null, List.of(limitLeg("AAPL", Side.BUY, 100, "150.00"))));
    var legId = view.legs().get(0).legOrderId();

    assertThat(registry.recordLegFill(legId, 100, true)).contains(view.basketId());
    assertThat(registry.view(view.basketId()).orElseThrow().status())
        .isEqualTo(BasketStatus.FILLED);
  }

  @Test
  void submit_limitLegWithoutPrice_isRejectedBeforeAnyVenueCall() {
    var request =
        new BasketOrderRequest(
            null,
            List.of(
                new BasketLegRequest("AAPL", Side.BUY, OrderType.LIMIT, 100, null, null, null)));

    assertThatThrownBy(() -> service.submit(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires a limitPrice");
    assertThat(registry.trackedBaskets()).isZero();
  }

  @Test
  void submit_parentManagedOrderTypeLeg_isRejected() {
    var request =
        new BasketOrderRequest(
            null,
            List.of(
                new BasketLegRequest(
                    "AAPL",
                    Side.BUY,
                    OrderType.ICEBERG,
                    100,
                    new BigDecimal("150.00"),
                    null,
                    null)));

    assertThatThrownBy(() -> service.submit(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ICEBERG");
  }

  @Test
  void submit_emptyBasket_isRejected() {
    assertThatThrownBy(() -> service.submit(new BasketOrderRequest("empty", List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void rejectSymbol(String symbol) {
    doAnswer(
            inv -> {
              Order o = inv.getArgument(0);
              if (o.getSymbol().equals(symbol)) {
                o.compareAndSetStatus(OrderStatus.NEW, OrderStatus.REJECTED);
              }
              return o;
            })
        .when(executionService)
        .submitOrder(any(Order.class));
  }
}
