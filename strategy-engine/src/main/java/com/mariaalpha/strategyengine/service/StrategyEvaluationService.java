package com.mariaalpha.strategyengine.service;

import com.mariaalpha.proto.signal.Direction;
import com.mariaalpha.strategyengine.config.MlConfig;
import com.mariaalpha.strategyengine.metrics.StrategyMetrics;
import com.mariaalpha.strategyengine.ml.MlSignalClient;
import com.mariaalpha.strategyengine.ml.MlSignalResult;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.Side;
import com.mariaalpha.strategyengine.publisher.SignalPublisher;
import com.mariaalpha.strategyengine.routing.SymbolStrategyRouter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StrategyEvaluationService {

  private static final Logger LOG = LoggerFactory.getLogger(StrategyEvaluationService.class);

  private final SymbolStrategyRouter router;
  private final MlSignalClient mlClient;
  private final SignalPublisher signalPublisher;
  private final StrategyMetrics metrics;
  private final double confidenceThreshold;

  public StrategyEvaluationService(
      SymbolStrategyRouter router,
      MlSignalClient mlClient,
      SignalPublisher signalPublisher,
      StrategyMetrics metrics,
      MlConfig mlConfig) {
    this.router = router;
    this.mlClient = mlClient;
    this.signalPublisher = signalPublisher;
    this.metrics = metrics;
    this.confidenceThreshold = mlConfig.confidenceThreshold();
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

    var signal = signalOptional.get();

    long mlStart = System.currentTimeMillis();
    var mlResult = mlClient.getSignal(tick.symbol());
    metrics.recordMlLatency(System.currentTimeMillis() - mlStart);

    if (shouldSuppress(signal, mlResult)) {
      LOG.info(
          "ML signal contradicts strategy for {} (confidence={}) — suppressing",
          tick.symbol(),
          mlResult.map(r -> String.valueOf(r.confidence())).orElse("n/a"));
      return;
    }

    signalPublisher.publish(signal);
    metrics.recordSignal(strategy.name(), signal.side());
  }

  /**
   * Returns {@code true} only when the ML result has high confidence AND its direction contradicts
   * the strategy signal. In all other cases (ML unavailable, low confidence, agrees, or NEUTRAL)
   * the signal is allowed through.
   */
  private boolean shouldSuppress(OrderSignal signal, Optional<MlSignalResult> mlResult) {
    if (mlResult.isEmpty()) {
      return false; // ML unavailable, proceed
    }
    var mlSignalResult = mlResult.get();
    if (mlSignalResult.confidence() <= confidenceThreshold) {
      return false; // low confidence, proceed regardless of direction
    }
    return !directionsAgree(signal.side(), mlSignalResult.direction());
  }

  private static boolean directionsAgree(Side side, Direction direction) {
    if (direction == Direction.NEUTRAL) {
      return true; // NEUTRAL never contradicts
    }
    return (side == Side.BUY && direction == Direction.LONG)
        || (side == Side.SELL && direction == Direction.SHORT);
  }
}
