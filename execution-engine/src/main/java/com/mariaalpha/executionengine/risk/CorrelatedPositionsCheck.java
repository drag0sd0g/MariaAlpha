package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.config.RiskLimitsConfig.CorrelatedCluster;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import com.mariaalpha.executionengine.service.PositionTracker;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(10)
public class CorrelatedPositionsCheck implements RiskCheck {

  private final RiskLimitsConfig config;
  private final MarketStateTracker marketStateTracker;
  private final PositionTracker positionTracker;

  public CorrelatedPositionsCheck(
      RiskLimitsConfig config,
      MarketStateTracker marketStateTracker,
      PositionTracker positionTracker) {
    this.config = config;
    this.marketStateTracker = marketStateTracker;
    this.positionTracker = positionTracker;
  }

  @Override
  public String name() {
    return "CorrelatedPositions";
  }

  @Override
  public RiskCheckResult check(Order order) {
    var clusters = config.correlatedClusters();
    if (clusters == null || clusters.isEmpty()) {
      return RiskCheckResult.pass(name());
    }

    var clustersForOrder = clustersContaining(clusters, order.getSymbol());
    if (clustersForOrder.isEmpty()) {
      return RiskCheckResult.pass(name());
    }

    var market = marketStateTracker.getMarketState(order.getSymbol());
    if (market == null || market.lastTradePrice() == null) {
      return RiskCheckResult.fail(
          name(), "Market data unavailable for symbol: " + order.getSymbol());
    }
    var orderNotional = market.lastTradePrice().multiply(BigDecimal.valueOf(order.getQuantity()));

    for (var cluster : clustersForOrder) {
      if (cluster.limit() <= 0) {
        continue;
      }
      var current = clusterGross(cluster);
      var projected =
          projectedClusterGross(cluster, order.getSymbol(), order.getSide(), orderNotional);
      if (projected.compareTo(BigDecimal.valueOf(cluster.limit())) > 0
          && projected.compareTo(current) > 0) {
        return RiskCheckResult.fail(
            name(),
            String.format(
                "Projected gross exposure $%s in cluster '%s' exceeds limit of $%d",
                projected.toPlainString(), cluster.name(), cluster.limit()));
      }
    }
    return RiskCheckResult.pass(name());
  }

  private List<CorrelatedCluster> clustersContaining(
      List<CorrelatedCluster> clusters, String symbol) {
    return clusters.stream().filter(c -> symbolsOf(c).contains(symbol)).toList();
  }

  private BigDecimal clusterGross(CorrelatedCluster cluster) {
    var positions = positionTracker.snapshot();
    var symbols = symbolsOf(cluster);
    return positions.entrySet().stream()
        .filter(e -> symbols.contains(e.getKey()))
        .map(e -> e.getValue().abs())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal projectedClusterGross(
      CorrelatedCluster cluster, String orderSymbol, Side side, BigDecimal orderNotional) {
    var positions = positionTracker.snapshot();
    var symbols = symbolsOf(cluster);
    var projected = BigDecimal.ZERO;
    boolean orderSymbolSeen = false;
    for (var entry : positions.entrySet()) {
      if (!symbols.contains(entry.getKey())) {
        continue;
      }
      if (entry.getKey().equals(orderSymbol)) {
        var delta = side == Side.BUY ? orderNotional : orderNotional.negate();
        projected = projected.add(entry.getValue().add(delta).abs());
        orderSymbolSeen = true;
      } else {
        projected = projected.add(entry.getValue().abs());
      }
    }
    if (!orderSymbolSeen) {
      var delta = side == Side.BUY ? orderNotional : orderNotional.negate();
      projected = projected.add(delta.abs());
    }
    return projected;
  }

  private static Set<String> symbolsOf(CorrelatedCluster cluster) {
    return cluster.symbols() == null ? Set.of() : Set.copyOf(cluster.symbols());
  }
}
