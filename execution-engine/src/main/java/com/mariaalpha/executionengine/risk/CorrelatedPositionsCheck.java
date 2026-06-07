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

/**
 * Pre-trade correlated-positions concentration check (roadmap 3.5.2).
 *
 * <p>Models the case where the standard sector / beta checks pass — gross exposure looks fine, the
 * portfolio's β is in the green — but the underlying mix has concentrated into a small set of
 * symbols whose returns historically move together. Two megacap-tech names plus a mega-cap chip
 * stock might span two sectors and present a normal β, yet a single drawdown in the AI trade would
 * take all three down at once.
 *
 * <p>Each {@link CorrelatedCluster} is a named list of symbols plus a $-cap on gross exposure
 * within that cluster. The check evaluates every cluster the order touches and rejects when the
 * projected gross within the cluster exceeds the cluster's cap (and would grow vs. the current
 * cluster gross — a SELL flattening a long inside the cluster always passes).
 *
 * <p>A symbol may appear in multiple clusters; each is evaluated independently. Symbols outside any
 * cluster are not constrained by this check. If the configured cluster list is empty the check
 * self-disables.
 */
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
      // No existing position on the order's symbol; add it iff BUY (a fresh SELL would create a
      // short that does grow the gross — handled in the same branch).
      var delta = side == Side.BUY ? orderNotional : orderNotional.negate();
      projected = projected.add(delta.abs());
    }
    return projected;
  }

  private static Set<String> symbolsOf(CorrelatedCluster cluster) {
    return cluster.symbols() == null ? Set.of() : Set.copyOf(cluster.symbols());
  }
}
