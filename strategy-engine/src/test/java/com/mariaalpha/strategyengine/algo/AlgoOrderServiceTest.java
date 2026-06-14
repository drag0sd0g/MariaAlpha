package com.mariaalpha.strategyengine.algo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.strategyengine.model.Side;
import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import com.mariaalpha.strategyengine.routing.SymbolStrategyRouter;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlgoOrderServiceTest {

  @Mock private StrategyRegistry strategyRegistry;
  @Mock private SymbolStrategyRouter router;
  @Mock private AlgoProgressPublisher progressPublisher;
  @Mock private TradingStrategy strategy;

  private AlgoOrderRegistry orderRegistry;
  private AlgoOrderService service;

  @BeforeEach
  void setUp() {
    orderRegistry = new AlgoOrderRegistry();
    service = new AlgoOrderService(strategyRegistry, router, orderRegistry, progressPublisher);
  }

  @Test
  void submitCreatesActiveAlgoOrderAppliesParamsAndBindsRouter() {
    when(strategyRegistry.get("VWAP")).thenReturn(Optional.of(strategy));
    var params = Map.<String, Object>of("targetQuantity", 100);
    var req = new AlgoOrderRequest("AAPL", Side.BUY, 100, "VWAP", params);

    var algo = service.submit(req);

    assertThat(algo.status()).isEqualTo(AlgoOrder.Status.ACTIVE);
    assertThat(algo.symbol()).isEqualTo("AAPL");
    assertThat(algo.targetQuantity()).isEqualTo(100);
    // Caller params are forwarded, enriched with the order's side (and targetQuantity when the
    // caller didn't supply one) so the strategy can't run with a stale side from a prior order.
    verify(strategy).updateParameters(Map.of("targetQuantity", 100, "side", "BUY"));
    verify(router).setActiveStrategy("AAPL", "VWAP");
    verify(progressPublisher).publishLifecycle(algo, AlgoProgressEvent.EventType.CREATED);
    assertThat(orderRegistry.find(algo.algoOrderId())).contains(algo);
  }

  @Test
  void submitWithoutParametersStillAppliesSideAndQuantity() {
    when(strategyRegistry.get("VWAP")).thenReturn(Optional.of(strategy));
    var req = new AlgoOrderRequest("AAPL", Side.SELL, 250, "VWAP", null);
    service.submit(req);
    verify(strategy).updateParameters(Map.of("side", "SELL", "targetQuantity", 250L));
    verify(router).setActiveStrategy(anyString(), anyString());
  }

  @Test
  void submitThrowsForUnknownStrategy() {
    when(strategyRegistry.get("BOGUS")).thenReturn(Optional.empty());
    var req = new AlgoOrderRequest("AAPL", Side.BUY, 100, "BOGUS", Map.of());
    assertThatThrownBy(() -> service.submit(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown strategy: BOGUS");
    verify(router, never()).setActiveStrategy(anyString(), anyString());
    verify(progressPublisher, never()).publishLifecycle(any(), any());
  }

  @Test
  void cancelTransitionsToCancelledUnbindsRouterAndPublishesEvent() {
    when(strategyRegistry.get("VWAP")).thenReturn(Optional.of(strategy));
    var algo = service.submit(new AlgoOrderRequest("AAPL", Side.BUY, 100, "VWAP", Map.of()));

    var cancelled = service.cancel(algo.algoOrderId());

    assertThat(cancelled).isPresent();
    assertThat(cancelled.get().status()).isEqualTo(AlgoOrder.Status.CANCELLED);
    verify(router).clearActiveStrategy("AAPL");
    verify(progressPublisher)
        .publishLifecycle(cancelled.get(), AlgoProgressEvent.EventType.CANCELLED);
  }

  @Test
  void cancelReturnsEmptyForUnknownId() {
    assertThat(service.cancel(UUID.randomUUID())).isEmpty();
    verify(router, never()).clearActiveStrategy(anyString());
  }

  @Test
  void cancelOnAlreadyCancelledIsIdempotent() {
    when(strategyRegistry.get("VWAP")).thenReturn(Optional.of(strategy));
    var algo = service.submit(new AlgoOrderRequest("AAPL", Side.BUY, 100, "VWAP", Map.of()));
    service.cancel(algo.algoOrderId());
    // Second cancel returns the already-terminal record but does not re-publish or re-unbind.
    var again = service.cancel(algo.algoOrderId());
    assertThat(again).isPresent();
    assertThat(again.get().status()).isEqualTo(AlgoOrder.Status.CANCELLED);
    verify(router, org.mockito.Mockito.times(1)).clearActiveStrategy("AAPL");
  }
}
