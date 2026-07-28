package com.mariaalpha.strategyengine.backtest;

import com.mariaalpha.strategyengine.backtest.data.AlpacaHistoricalClient;
import com.mariaalpha.strategyengine.backtest.data.BarToTickSynthesizer;
import com.mariaalpha.strategyengine.backtest.time.SimulationClock;
import com.mariaalpha.strategyengine.metrics.StrategyMetrics;
import com.mariaalpha.strategyengine.ml.MlSignalClient;
import com.mariaalpha.strategyengine.ml.MlSignalGate;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.Side;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Drives real, shipped {@link TradingStrategy} instances over a historical tick tape synthesized
 * from Alpaca 1-minute bars, simulating fills and accounting P&L to prove whether a strategy makes
 * money on historical data.
 *
 * <p>The replay is fully deterministic: a {@link SimulationClock} is advanced to each tick's
 * timestamp so every time-stamped output is reproducible. Each symbol gets its own fresh strategy
 * instance so the run never touches live strategy state.
 */
@Service
public class BacktestEngine {

  private static final Logger LOG = LoggerFactory.getLogger(BacktestEngine.class);
  private static final BigDecimal DEFAULT_INITIAL_CASH = BigDecimal.valueOf(100_000);
  private static final BigDecimal BPS = BigDecimal.valueOf(10_000);
  private static final String DATA_NOTE =
      "Alpha P&L is indicative: fills are simulated against a modelled bid/ask spread synthesized "
          + "from 1-minute OHLCV bars (Alpaca IEX). Execution-quality metrics (slippage vs "
          + "arrival) reflect that modelled spread. A real trades+quotes tape is required for "
          + "definitive alpha attribution.";

  private final AlpacaHistoricalClient historicalClient;
  private final BarToTickSynthesizer synthesizer;
  private final BacktestStrategyFactory strategyFactory;
  private final MlSignalClient mlClient;
  private final MlSignalGate mlGate;
  private final StrategyMetrics strategyMetrics;
  private final BacktestProperties properties;
  private final Clock clock;

  public BacktestEngine(
      AlpacaHistoricalClient historicalClient,
      BarToTickSynthesizer synthesizer,
      BacktestStrategyFactory strategyFactory,
      MlSignalClient mlClient,
      MlSignalGate mlGate,
      StrategyMetrics strategyMetrics,
      BacktestProperties properties,
      Clock clock) {
    this.historicalClient = historicalClient;
    this.synthesizer = synthesizer;
    this.strategyFactory = strategyFactory;
    this.mlClient = mlClient;
    this.mlGate = mlGate;
    this.strategyMetrics = strategyMetrics;
    this.properties = properties;
    this.clock = clock;
  }

  public BacktestResult run(BacktestRequest request) {
    validate(request);

    var to = request.to() != null ? request.to() : clock.instant();
    int lookbackDays =
        request.lookbackDays() != null ? request.lookbackDays() : properties.lookbackDays();
    var from = request.from() != null ? request.from() : to.minus(Duration.ofDays(lookbackDays));
    double slippageBps =
        request.slippageBps() != null ? request.slippageBps() : properties.slippageBps();
    var initialCash = request.initialCash() != null ? request.initialCash() : DEFAULT_INITIAL_CASH;

    var strategies = new HashMap<String, TradingStrategy>();
    var allTicks = new ArrayList<MarketTick>();
    int barsReplayed = 0;
    for (var symbol : request.symbols()) {
      var bars = historicalClient.fetchMinuteBars(symbol, from, to);
      barsReplayed += bars.size();
      allTicks.addAll(synthesizer.toTicks(bars));
      var strategy = strategyFactory.freshInstance(request.strategyName());
      strategy.updateParameters(new HashMap<>(request.parameters()));
      strategies.put(symbol, strategy);
    }
    allTicks.sort(Comparator.comparing(MarketTick::timestamp));

    if (allTicks.isEmpty()) {
      LOG.warn(
          "Backtest for {} on {} returned no bars in [{}, {}) — check credentials/date range",
          request.strategyName(),
          request.symbols(),
          from,
          to);
      return emptyResult(request, from, to);
    }

    var result =
        replay(request, allTicks, strategies, from, to, slippageBps, initialCash, barsReplayed);
    LOG.info(
        "Backtest {} on {}: {} bars, {} fills, return={}%, sharpe={}",
        request.strategyName(),
        request.symbols(),
        barsReplayed,
        result.metrics().totalFills(),
        round2(result.metrics().totalReturnPct()),
        round2(result.metrics().sharpe()));
    return result;
  }

  private BacktestResult replay(
      BacktestRequest request,
      List<MarketTick> allTicks,
      Map<String, TradingStrategy> strategies,
      Instant from,
      Instant to,
      double slippageBps,
      BigDecimal initialCash,
      int barsReplayed) {
    var simClock = new SimulationClock(allTicks.get(0).timestamp());
    var fillModel = new BacktestFillModel(slippageBps);
    var portfolio = new BacktestPortfolio(initialCash);
    var marks = new HashMap<String, BigDecimal>();
    var curve = new ArrayList<EquityPoint>();
    var trades = new ArrayList<TradeRecord>();
    long lastMinute = Long.MIN_VALUE;

    for (var tick : allTicks) {
      simClock.advanceTo(tick.timestamp());
      var now = simClock.instant();
      marks.put(tick.symbol(), mark(tick));

      for (var fill : fillModel.onTick(tick, now)) {
        recordFill(fill, portfolio, trades);
      }

      var strategy = strategies.get(tick.symbol());
      strategy.onTick(tick);
      var signal = strategy.evaluate(tick.symbol());
      if (signal.isPresent()) {
        var gated = applyMlGate(signal.get(), tick.symbol(), request.useMlGate());
        if (gated.isPresent()) {
          for (var fill : fillModel.onSignal(gated.get(), tick, now)) {
            recordFill(fill, portfolio, trades);
          }
        }
      }

      long minute = tick.timestamp().getEpochSecond() / 60;
      if (minute != lastMinute) {
        curve.add(new EquityPoint(tick.timestamp(), portfolio.equity(marks)));
        lastMinute = minute;
      }
    }
    curve.add(
        new EquityPoint(allTicks.get(allTicks.size() - 1).timestamp(), portfolio.equity(marks)));

    var metrics = buildMetrics(portfolio, curve, trades, marks);
    return new BacktestResult(
        request.strategyName(),
        request.symbols(),
        from,
        to,
        barsReplayed,
        allTicks.size(),
        request.useMlGate(),
        DATA_NOTE,
        metrics,
        curve,
        trades,
        null);
  }

  private Optional<OrderSignal> applyMlGate(OrderSignal signal, String symbol, boolean useMlGate) {
    if (!useMlGate) {
      return Optional.of(signal);
    }
    var mlResult = mlClient.getSignal(symbol);
    var decision = mlGate.decide(signal, mlResult);
    strategyMetrics.recordMlDecision(decision.outcome(), signal.strategyName(), signal.side());
    return decision.signal();
  }

  private void recordFill(
      BacktestFill fill, BacktestPortfolio portfolio, List<TradeRecord> trades) {
    var realized = portfolio.apply(fill);
    trades.add(
        new TradeRecord(
            fill.timestamp(),
            fill.symbol(),
            fill.side(),
            fill.quantity(),
            fill.price(),
            fill.arrivalMid(),
            slippageBps(fill),
            realized,
            fill.passive()));
  }

  private static double slippageBps(BacktestFill fill) {
    var arrival = fill.arrivalMid();
    if (arrival == null || arrival.signum() == 0) {
      return 0.0;
    }
    var raw =
        fill.side() == Side.BUY ? fill.price().subtract(arrival) : arrival.subtract(fill.price());
    return raw.divide(arrival, 10, RoundingMode.HALF_UP).multiply(BPS).doubleValue();
  }

  private BacktestMetrics buildMetrics(
      BacktestPortfolio portfolio,
      List<EquityPoint> curve,
      List<TradeRecord> trades,
      Map<String, BigDecimal> marks) {
    var initial = portfolio.initialCash();
    var finalEquity = curve.isEmpty() ? initial : curve.get(curve.size() - 1).equity();
    int closed = portfolio.closedTrades();
    double hitRate = closed == 0 ? 0.0 : (double) portfolio.winningTrades() / closed;
    double avgSlippage =
        trades.isEmpty()
            ? 0.0
            : trades.stream().mapToDouble(TradeRecord::slippageBps).average().orElse(0.0);
    return new BacktestMetrics(
        BacktestMetricsCalculator.totalReturnPct(initial, finalEquity),
        BacktestMetricsCalculator.annualizedSharpe(curve),
        BacktestMetricsCalculator.maxDrawdownPct(curve),
        hitRate,
        closed,
        trades.size(),
        avgSlippage,
        initial,
        finalEquity,
        portfolio.realizedPnl(),
        portfolio.unrealized(marks));
  }

  private BacktestResult emptyResult(BacktestRequest request, Instant from, Instant to) {
    var cash = request.initialCash() != null ? request.initialCash() : DEFAULT_INITIAL_CASH;
    var metrics =
        new BacktestMetrics(
            0.0, 0.0, 0.0, 0.0, 0, 0, 0.0, cash, cash, BigDecimal.ZERO, BigDecimal.ZERO);
    return new BacktestResult(
        request.strategyName(),
        request.symbols(),
        from,
        to,
        0,
        0,
        request.useMlGate(),
        DATA_NOTE,
        metrics,
        List.of(),
        List.of(),
        null);
  }

  private static BigDecimal mark(MarketTick tick) {
    var bid = tick.bidPrice();
    var ask = tick.askPrice();
    if (bid != null && ask != null && bid.signum() > 0 && ask.signum() > 0) {
      return bid.add(ask).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }
    return tick.price();
  }

  private static void validate(BacktestRequest request) {
    if (request.strategyName() == null || request.strategyName().isBlank()) {
      throw new IllegalArgumentException("strategyName is required");
    }
    if (request.symbols().isEmpty()) {
      throw new IllegalArgumentException("at least one symbol is required");
    }
  }

  private static double round2(double value) {
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }
}
