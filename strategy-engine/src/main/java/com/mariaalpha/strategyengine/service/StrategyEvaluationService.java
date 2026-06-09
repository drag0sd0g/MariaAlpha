package com.mariaalpha.strategyengine.service;

import com.mariaalpha.strategyengine.metrics.StrategyMetrics;
import com.mariaalpha.strategyengine.ml.MlGateDecision;
import com.mariaalpha.strategyengine.ml.MlSignalClient;
import com.mariaalpha.strategyengine.ml.MlSignalGate;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.publisher.SignalPublisher;
import com.mariaalpha.strategyengine.routing.RegimeBasedStrategySelector;
import com.mariaalpha.strategyengine.routing.SymbolStrategyRouter;
import com.mariaalpha.strategyengine.sessions.TradingHoursService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StrategyEvaluationService {

  private static final Logger LOG = LoggerFactory.getLogger(StrategyEvaluationService.class);

  private final SymbolStrategyRouter router;
  private final RegimeBasedStrategySelector regimeSelector;
  private final MlSignalClient mlClient;
  private final MlSignalGate mlGate;
  private final SignalPublisher signalPublisher;
  private final StrategyMetrics metrics;
  private final TradingHoursService tradingHoursService;

  public StrategyEvaluationService(
      SymbolStrategyRouter router,
      RegimeBasedStrategySelector regimeSelector,
      MlSignalClient mlClient,
      MlSignalGate mlGate,
      SignalPublisher signalPublisher,
      StrategyMetrics metrics,
      TradingHoursService tradingHoursService) {
    this.router = router;
    this.regimeSelector = regimeSelector;
    this.mlClient = mlClient;
    this.mlGate = mlGate;
    this.signalPublisher = signalPublisher;
    this.metrics = metrics;
    this.tradingHoursService = tradingHoursService;
  }

  public void evaluate(MarketTick tick) {
    // Roadmap 3.1.3 — drop ticks for markets that are closed at the tick's timestamp so
    // indicator-driven strategies (Momentum's EMAs / RSI) don't drift on out-of-hours quotes.
    // The gate is a soft no-op when disabled in config, preserving pre-3.1.3 behaviour.
    if (!tradingHoursService.isMarketOpen(tick.symbol(), tick.timestamp())) {
      metrics.recordTickSuppressed(tick.symbol(), "market_closed");
      return;
    }
    // FR-17: when regime auto-select is enabled and the ML service produces a high-confidence
    // regime, route to the strategy the regime prescribes. Otherwise fall back to the manual
    // SymbolStrategyRouter binding.
    var strategyOptional = regimeSelector.selectFor(tick.symbol());
    if (strategyOptional.isEmpty()) {
      strategyOptional = router.getActiveStrategy(tick.symbol());
    }
    if (strategyOptional.isEmpty()) {
      return;
    }
    var strategy = strategyOptional.get();
    strategy.onTick(tick);

    long evalStart = System.currentTimeMillis();
    var signalOptional = strategy.evaluate(tick.symbol());
    metrics.recordEvaluationDuration(strategy.name(), System.currentTimeMillis() - evalStart);

    if (signalOptional.isEmpty()) {
      return;
    }
    var strategySignal = signalOptional.get();

    long mlStart = System.currentTimeMillis();
    var mlResult = mlClient.getSignal(tick.symbol());
    metrics.recordMlLatency(System.currentTimeMillis() - mlStart);

    var decision = mlGate.decide(strategySignal, mlResult);
    metrics.recordMlDecision(decision.outcome(), strategy.name(), strategySignal.side());

    if (decision.signal().isEmpty()) {
      LOG.info(
          "ML gate {} — suppressing strategy {} for {}",
          decision.outcome(),
          strategy.name(),
          tick.symbol());
      return;
    }
    var publishable = decision.signal().get();
    if (decision.outcome() == MlGateDecision.Outcome.CONFIRMED) {
      metrics.recordMlQuantityScale(strategy.name(), decision.quantityScale());
    }

    signalPublisher.publish(publishable);
    metrics.recordSignal(strategy.name(), publishable.side());
  }
}
