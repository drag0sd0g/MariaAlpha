package com.mariaalpha.strategyengine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.doubleThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.proto.signal.Direction;
import com.mariaalpha.strategyengine.config.MlConfig;
import com.mariaalpha.strategyengine.config.MlConfig.SizingMode;
import com.mariaalpha.strategyengine.config.MlConfig.VetoMode;
import com.mariaalpha.strategyengine.metrics.StrategyMetrics;
import com.mariaalpha.strategyengine.ml.MlGateDecision;
import com.mariaalpha.strategyengine.ml.MlSignalClient;
import com.mariaalpha.strategyengine.ml.MlSignalGate;
import com.mariaalpha.strategyengine.ml.MlSignalResult;
import com.mariaalpha.strategyengine.model.DataSource;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import com.mariaalpha.strategyengine.publisher.SignalPublisher;
import com.mariaalpha.strategyengine.routing.RegimeBasedStrategySelector;
import com.mariaalpha.strategyengine.routing.SymbolStrategyRouter;
import com.mariaalpha.strategyengine.sessions.TradingHoursService;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class StrategyEvaluationServiceTest {

  @Mock private SymbolStrategyRouter router;
  @Mock private RegimeBasedStrategySelector regimeSelector;
  @Mock private MlSignalClient mlClient;
  @Mock private SignalPublisher signalPublisher;
  @Mock private StrategyMetrics metrics;
  @Mock private TradingStrategy strategy;
  @Mock private TradingHoursService tradingHoursService;

  private StrategyEvaluationService underTest;

  private static final String SYMBOL = "AAPL";
  private static final OrderSignal BUY_SIGNAL =
      new OrderSignal(
          SYMBOL, Side.BUY, 100, OrderType.LIMIT, new BigDecimal("178.54"), "VWAP", Instant.now());

  @BeforeEach
  void setUp() {
    var config =
        new MlConfig(
            "localhost", 50051, 0.7, VetoMode.STRICT, false, SizingMode.NONE, 0.2, 0.5, 1.5);
    var gate = new MlSignalGate(config);
    underTest =
        new StrategyEvaluationService(
            router, regimeSelector, mlClient, gate, signalPublisher, metrics, tradingHoursService);
    // Lenient because the regime-selector test overrides this and skips the router path.
    lenient().when(regimeSelector.selectFor(SYMBOL)).thenReturn(Optional.empty());
    lenient().when(router.getActiveStrategy(SYMBOL)).thenReturn(Optional.of(strategy));
    // Most tests assume the market is open — the trading-hours-gate test overrides this.
    lenient().when(tradingHoursService.isMarketOpen(any(), any())).thenReturn(true);
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
    verify(metrics).recordMlDecision(MlGateDecision.Outcome.NO_ML, "VWAP", Side.BUY);
  }

  @Test
  void evaluateSuppressesSignalWhenMlHighConfidenceContradicts() {
    when(strategy.name()).thenReturn("VWAP");
    when(strategy.evaluate(SYMBOL)).thenReturn(Optional.of(BUY_SIGNAL));
    when(mlClient.getSignal(SYMBOL))
        .thenReturn(Optional.of(new MlSignalResult(Direction.SHORT, 0.9, 0.0)));
    underTest.evaluate(tick(SYMBOL));
    verify(signalPublisher, never()).publish(any());
    verify(metrics).recordMlDecision(MlGateDecision.Outcome.VETOED, "VWAP", Side.BUY);
  }

  @Test
  void evaluateProceedsWhenMlHighConfidenceAgrees() {
    when(strategy.name()).thenReturn("VWAP");
    when(strategy.evaluate(SYMBOL)).thenReturn(Optional.of(BUY_SIGNAL));
    when(mlClient.getSignal(SYMBOL))
        .thenReturn(Optional.of(new MlSignalResult(Direction.LONG, 0.85, 0.0)));
    underTest.evaluate(tick(SYMBOL));
    verify(signalPublisher).publish(BUY_SIGNAL);
    verify(metrics).recordMlDecision(MlGateDecision.Outcome.CONFIRMED, "VWAP", Side.BUY);
  }

  @Test
  void evaluateProceedsWhenMlLowConfidenceEvenIfContradicts() {
    when(strategy.name()).thenReturn("VWAP");
    when(strategy.evaluate(SYMBOL)).thenReturn(Optional.of(BUY_SIGNAL));
    when(mlClient.getSignal(SYMBOL))
        .thenReturn(Optional.of(new MlSignalResult(Direction.SHORT, 0.5, 0.0)));
    underTest.evaluate(tick(SYMBOL));
    verify(signalPublisher).publish(BUY_SIGNAL);
    verify(metrics).recordMlDecision(MlGateDecision.Outcome.LOW_CONFIDENCE, "VWAP", Side.BUY);
  }

  @Test
  void evaluatePermissiveModePublishesContradictionButTagsOverridden() {
    var permissiveConfig =
        new MlConfig(
            "localhost", 50051, 0.7, VetoMode.PERMISSIVE, false, SizingMode.NONE, 0.2, 0.5, 1.5);
    var permissiveGate = new MlSignalGate(permissiveConfig);
    var permissiveService =
        new StrategyEvaluationService(
            router,
            regimeSelector,
            mlClient,
            permissiveGate,
            signalPublisher,
            metrics,
            tradingHoursService);

    when(strategy.name()).thenReturn("VWAP");
    when(strategy.evaluate(SYMBOL)).thenReturn(Optional.of(BUY_SIGNAL));
    when(mlClient.getSignal(SYMBOL))
        .thenReturn(Optional.of(new MlSignalResult(Direction.SHORT, 0.9, 0.0)));

    permissiveService.evaluate(tick(SYMBOL));

    verify(signalPublisher).publish(BUY_SIGNAL);
    verify(metrics).recordMlDecision(MlGateDecision.Outcome.OVERRIDDEN, "VWAP", Side.BUY);
  }

  @Test
  void evaluateScalesQuantityWhenSizingModeScaledAndMlAgrees() {
    var sizingConfig =
        new MlConfig(
            "localhost", 50051, 0.7, VetoMode.STRICT, false, SizingMode.SCALED, 0.20, 0.50, 1.50);
    var sizingGate = new MlSignalGate(sizingConfig);
    var sizingService =
        new StrategyEvaluationService(
            router,
            regimeSelector,
            mlClient,
            sizingGate,
            signalPublisher,
            metrics,
            tradingHoursService);

    when(strategy.name()).thenReturn("VWAP");
    when(strategy.evaluate(SYMBOL)).thenReturn(Optional.of(BUY_SIGNAL));
    // recommendedSize=0.30 → scale = 0.30 / 0.20 = 1.5x → 100 * 1.5 = 150
    when(mlClient.getSignal(SYMBOL))
        .thenReturn(Optional.of(new MlSignalResult(Direction.LONG, 0.85, 0.30)));

    sizingService.evaluate(tick(SYMBOL));

    var captor = ArgumentCaptor.forClass(OrderSignal.class);
    verify(signalPublisher).publish(captor.capture());
    assertThat(captor.getValue().quantity()).isEqualTo(150);
    verify(metrics).recordMlQuantityScale(eq("VWAP"), doubleThat(d -> Math.abs(d - 1.5) < 1e-9));
  }

  @Test
  void evaluateUsesRegimeSelectorWhenItReturnsAStrategy() {
    // Regime-driven selection overrides whatever the manual router would say (FR-17).
    when(regimeSelector.selectFor(SYMBOL)).thenReturn(Optional.of(strategy));
    when(strategy.name()).thenReturn("MOMENTUM");
    when(strategy.evaluate(SYMBOL)).thenReturn(Optional.of(BUY_SIGNAL));
    when(mlClient.getSignal(SYMBOL)).thenReturn(Optional.empty());

    underTest.evaluate(tick(SYMBOL));

    verify(signalPublisher).publish(BUY_SIGNAL);
    verify(metrics).recordSignal("MOMENTUM", Side.BUY);
    // Manual router should not be consulted when the regime selector returns a strategy.
    verify(router, never()).getActiveStrategy(SYMBOL);
  }

  @Test
  void evaluateSuppressesTickWhenMarketClosed() {
    when(tradingHoursService.isMarketOpen(eq(SYMBOL), any())).thenReturn(false);
    underTest.evaluate(tick(SYMBOL));
    verify(metrics).recordTickSuppressed(SYMBOL, "market_closed");
    verify(router, never()).getActiveStrategy(any());
    verify(regimeSelector, never()).selectFor(any());
    verify(signalPublisher, never()).publish(any());
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
