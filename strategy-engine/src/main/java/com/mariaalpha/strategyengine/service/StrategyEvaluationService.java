package com.mariaalpha.strategyengine.service;

import com.mariaalpha.strategyengine.metrics.StrategyMetrics;
import com.mariaalpha.strategyengine.ml.MlGateDecision;
import com.mariaalpha.strategyengine.ml.MlSignalClient;
import com.mariaalpha.strategyengine.ml.MlSignalGate;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.publisher.SignalPublisher;
import com.mariaalpha.strategyengine.routing.SymbolStrategyRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StrategyEvaluationService {

  private static final Logger LOG = LoggerFactory.getLogger(StrategyEvaluationService.class);

  private final SymbolStrategyRouter router;
  private final MlSignalClient mlClient;
  private final MlSignalGate mlGate;
  private final SignalPublisher signalPublisher;
  private final StrategyMetrics metrics;

  public StrategyEvaluationService(
      SymbolStrategyRouter router,
      MlSignalClient mlClient,
      MlSignalGate mlGate,
      SignalPublisher signalPublisher,
      StrategyMetrics metrics) {
    this.router = router;
    this.mlClient = mlClient;
    this.mlGate = mlGate;
    this.signalPublisher = signalPublisher;
    this.metrics = metrics;
  }

  public void evaluate(MarketTick tick) {
    var strategyOptional = router.getActiveStrategy(tick.symbol());
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
