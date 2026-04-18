package com.mariaalpha.executionengine.adapter;

import com.mariaalpha.executionengine.config.SimulatedConfig;
import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.ExecutionReport;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderAck;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("simulated")
public class SimulatedExchangeAdapter implements ExchangeAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(SimulatedExchangeAdapter.class);

  private final SimulatedConfig config;
  private final MarketStateTracker marketStateTracker;
  private final ScheduledExecutorService scheduler;
  private final ConcurrentHashMap<String, ExecutionInstruction> pendingOrders;
  private volatile Consumer<ExecutionReport> reportCallback;

  public SimulatedExchangeAdapter(SimulatedConfig config, MarketStateTracker marketStateTracker) {
    this.config = config;
    this.marketStateTracker = marketStateTracker;
    this.scheduler = Executors.newSingleThreadScheduledExecutor();
    this.pendingOrders = new ConcurrentHashMap<>();
  }

  @Override
  public OrderAck submitOrder(ExecutionInstruction instruction) {
    var order = instruction.order();
    var exchangeId = "SIM-" + UUID.randomUUID().toString().substring(0, 8);
    order.setExchangeOrderId(exchangeId);

    var marketState = marketStateTracker.getMarketState(order.getSymbol());

    switch (order.getOrderType()) {
      case MARKET -> scheduleFill(exchangeId, order, marketState, config.fillLatencyMs());
      case LIMIT -> handleLimitOrder(exchangeId, order, marketState);
      case STOP -> pendingOrders.put(exchangeId, instruction); // wait for trigger
    }

    return new OrderAck(order.getOrderId(), exchangeId, true, "");
  }

  @Override
  public OrderAck cancelOrder(String exchangeOrderId) {
    var removed = pendingOrders.remove(exchangeOrderId);
    if (removed != null) {
      return new OrderAck(removed.order().getOrderId(), exchangeOrderId, true, "cancelled");
    }
    return new OrderAck("", exchangeOrderId, false, "order not found or already filled");
  }

  @Override
  public void onExecutionReport(Consumer<ExecutionReport> callback) {
    this.reportCallback = callback;
  }

  @Override
  public void start() {
    LOG.info(
        "Simulated exchange adapter started (latency={}ms, slippage={}bps",
        config.fillLatencyMs(),
        config.slippageBps());
  }

  @Override
  @PreDestroy
  public void shutdown() {
    scheduler.shutdownNow();
  }

  @Override
  public boolean isHealthy() {
    return true;
  }

  /**
   * Called by the MarketDataConsumer whenever a new tick arrives. Checks if any pending STOP orders
   * should be triggered.
   */
  public void onMarketUpdate(MarketState newState) {
    pendingOrders.forEach(
        (exchangeId, instruction) -> {
          var order = instruction.order();
          if (!order.getSymbol().equals(newState.symbol())) {
            return;
          }
          if (order.getOrderType() == OrderType.STOP) {
            boolean triggered =
                (order.getSide() == Side.BUY
                        && newState.lastTradePrice().compareTo(order.getStopPrice()) >= 0)
                    || (order.getSide() == Side.SELL
                        && newState.lastTradePrice().compareTo(order.getStopPrice()) <= 0);
            if (triggered) {
              LOG.info(
                  "STOP order {} triggered at market price {}",
                  exchangeId,
                  newState.lastTradePrice());
              pendingOrders.remove(exchangeId);
              scheduleFill(exchangeId, order, newState, config.fillLatencyMs());
            }
          } else if (order.getOrderType() == OrderType.LIMIT) {
            boolean canFill =
                (order.getSide() == Side.BUY
                        && order.getLimitPrice().compareTo(newState.askPrice()) >= 0)
                    || (order.getSide() == Side.SELL
                        && order.getLimitPrice().compareTo(newState.bidPrice()) <= 0);
            if (canFill) {
              LOG.info(
                  "LIMIT order {} now fillable at market bid/ask {}/{}",
                  exchangeId,
                  newState.bidPrice(),
                  newState.askPrice());
              pendingOrders.remove(exchangeId);
              scheduleFill(exchangeId, order, newState, config.fillLatencyMs());
            }
          }
        });
  }

  private void scheduleFill(String exchangeId, Order order, MarketState marketState, long delayMs) {
    scheduler.schedule(
        () -> {
          var fillPrice = computeFillPrice(order, marketState);
          int fillQty = computeFillQuantity(order);
          int remaining = order.getRemainingQuantity() - fillQty;
          var report =
              new ExecutionReport(
                  exchangeId, fillPrice, fillQty, remaining, config.venue(), Instant.now());
          LOG.info(
              "SIM fill: {} {} {} @ {} (remaining={})",
              order.getSide(),
              order.getSymbol(),
              fillQty,
              fillPrice,
              remaining);
          if (reportCallback != null) {
            reportCallback.accept(report);
          }
        },
        delayMs,
        TimeUnit.MILLISECONDS);
  }

  private int computeFillQuantity(Order order) {
    int remaining = order.getRemainingQuantity();
    int fillQuantity = (int) Math.ceil(remaining * config.partialFillRatio());
    return Math.min(fillQuantity, remaining);
  }

  private BigDecimal computeFillPrice(Order order, MarketState marketState) {
    var basePrice = order.getSide() == Side.BUY ? marketState.askPrice() : marketState.bidPrice();
    var slippageMultiplier =
        BigDecimal.valueOf(config.slippageBps())
            .divide(BigDecimal.valueOf(10000), 6, RoundingMode.HALF_UP);
    var slippageAmount = basePrice.multiply(slippageMultiplier);
    return order.getSide() == Side.BUY
        ? basePrice.add(slippageAmount)
        : basePrice.subtract(slippageAmount);
  }

  private void handleLimitOrder(String exchangeId, Order order, MarketState marketState) {
    boolean canFill =
        (order.getSide() == Side.BUY
                && order.getLimitPrice().compareTo(marketState.askPrice()) >= 0)
            || (order.getSide() == Side.SELL
                && order.getLimitPrice().compareTo(marketState.bidPrice()) <= 0);
    if (canFill) {
      scheduleFill(exchangeId, order, marketState, config.fillLatencyMs());
    } else {
      pendingOrders.put(exchangeId, new ExecutionInstruction(order, "day", order.getLimitPrice()));
      LOG.debug("LIMIT order {} resting - price not yet reached", exchangeId);
    }
  }
}
