package com.mariaalpha.strategyengine.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.proto.signal.Direction;
import com.mariaalpha.strategyengine.config.MlConfig;
import com.mariaalpha.strategyengine.metrics.StrategyMetrics;
import com.mariaalpha.strategyengine.ml.MlSignalClient;
import com.mariaalpha.strategyengine.ml.MlSignalResult;
import com.mariaalpha.strategyengine.model.DataSource;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import com.mariaalpha.strategyengine.publisher.SignalPublisher;
import com.mariaalpha.strategyengine.routing.SymbolStrategyRouter;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class StrategyEvaluationServiceTest {

  @Mock private SymbolStrategyRouter router;
  @Mock private MlSignalClient mlClient;
  @Mock private SignalPublisher signalPublisher;
  @Mock private StrategyMetrics metrics;
  @Mock private TradingStrategy strategy;

  private StrategyEvaluationService underTest;

  private static final String SYMBOL = "AAPL";
  private static final OrderSignal BUY_SIGNAL =
      new OrderSignal(
          SYMBOL, Side.BUY, 100, OrderType.LIMIT, new BigDecimal("178.54"), "VWAP", Instant.now());

  @BeforeEach
  void setUp() {
    underTest =
        new StrategyEvaluationService(
            router, mlClient, signalPublisher, metrics, new MlConfig("localhost", 50051, 0.7));
    when(router.getActiveStrategy(SYMBOL)).thenReturn(Optional.of(strategy));
  }

  @Test
  void evaluateSkipsWhenNoActiveStrategy() {
    when(router.getActiveStrategy(SYMBOL)).thenReturn(Optional.empty());
    underTest.evaluate(tick(SYMBOL));
    verify(signalPublisher, never()).publish(any());
  }

  @Test
  void evaluatePublishesSignalWhenMlUnavailable() {
    when(strategy.name()).thenReturn("VWAP");
    when(strategy.evaluate(SYMBOL)).thenReturn(Optional.of(BUY_SIGNAL));
    when(mlClient.getSignal(SYMBOL)).thenReturn(Optional.empty());
    underTest.evaluate(tick(SYMBOL));
    verify(signalPublisher).publish(BUY_SIGNAL);
    verify(metrics).recordSignal("VWAP", Side.BUY);
  }

  @Test
  void evaluateSuppressesSignalWhenMlHighConfidenceContradicts() {
    when(strategy.evaluate(SYMBOL)).thenReturn(Optional.of(BUY_SIGNAL));
    when(mlClient.getSignal(SYMBOL))
        .thenReturn(Optional.of(new MlSignalResult(Direction.SHORT, 0.9)));
    underTest.evaluate(tick(SYMBOL));
    verify(signalPublisher, never()).publish(any());
  }

  @Test
  void evaluateProceedsWhenMlHighConfidenceAgrees() {
    when(strategy.evaluate(SYMBOL)).thenReturn(Optional.of(BUY_SIGNAL));
    when(mlClient.getSignal(SYMBOL))
        .thenReturn(Optional.of(new MlSignalResult(Direction.LONG, 0.85)));
    underTest.evaluate(tick(SYMBOL));
    verify(signalPublisher).publish(BUY_SIGNAL);
  }

  @Test
  void evaluateProceedsWhenMlLowConfidenceEvenIfContradicts() {
    when(strategy.evaluate(SYMBOL)).thenReturn(Optional.of(BUY_SIGNAL));
    when(mlClient.getSignal(SYMBOL))
        .thenReturn(Optional.of(new MlSignalResult(Direction.SHORT, 0.5)));
    underTest.evaluate(tick(SYMBOL));
    verify(signalPublisher).publish(BUY_SIGNAL);
  }

  private static MarketTick tick(String symbol) {
    return new MarketTick(
        symbol,
        Instant.now(),
        EventType.QUOTE,
        BigDecimal.ZERO,
        0L,
        new BigDecimal("178.50"),
        new BigDecimal("178.54"),
        100L,
        80L,
        0L,
        DataSource.ALPACA,
        false);
  }
}
