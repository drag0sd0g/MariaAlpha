package com.mariaalpha.executionengine.risk;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import com.mariaalpha.executionengine.service.PositionTracker;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(6)
public class SectorExposureCheck implements RiskCheck {

  private final RiskLimitsConfig config;
  private final MarketStateTracker marketStateTracker;
  private final PositionTracker positionTracker;
  private final SymbolReferenceData refData;

  public SectorExposureCheck(
      RiskLimitsConfig config,
      MarketStateTracker marketStateTracker,
      PositionTracker positionTracker,
      SymbolReferenceData refData) {
    this.config = config;
    this.marketStateTracker = marketStateTracker;
    this.positionTracker = positionTracker;
    this.refData = refData;
  }

  @Override
  public String name() {
    return "SectorExposure";
  }

  @Override
  public RiskCheckResult check(Order order) {
    var sector = refData.sectorOf(order.getSymbol());
    long limit = limitFor(sector);
    if (limit <= 0) {
      return RiskCheckResult.pass(name());
    }

    var market = marketStateTracker.getMarketState(order.getSymbol());
    if (market == null || market.lastTradePrice() == null) {
      return RiskCheckResult.fail(
          name(), "Market data unavailable for symbol: " + order.getSymbol());
    }

    var sectorExposure = currentSectorExposure(sector);
    var orderNotional = market.lastTradePrice().multiply(BigDecimal.valueOf(order.getQuantity()));
    var projected =
        projectedSectorExposure(sector, order.getSymbol(), order.getSide(), orderNotional);

    if (projected.compareTo(BigDecimal.valueOf(limit)) > 0
        && projected.compareTo(sectorExposure) > 0) {
      return RiskCheckResult.fail(
          name(),
          String.format(
              "Projected %s sector exposure $%s exceeds limit of $%d",
              sector, projected.toPlainString(), limit));
    }
    return RiskCheckResult.pass(name());
  }

  private long limitFor(String sector) {
    var limits = config.sectorExposureLimits();
    if (limits != null && limits.containsKey(sector)) {
      return limits.get(sector);
    }
    return config.defaultSectorExposureLimit();
  }

  private BigDecimal currentSectorExposure(String sector) {
    return positionTracker.snapshot().entrySet().stream()
        .filter(e -> sector.equals(refData.sectorOf(e.getKey())))
        .map(e -> e.getValue().abs())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal projectedSectorExposure(
      String sector, String orderSymbol, Side side, BigDecimal orderNotional) {
    var projected = BigDecimal.ZERO;
    for (var entry : positionTracker.snapshot().entrySet()) {
      if (!sector.equals(refData.sectorOf(entry.getKey()))) {
        continue;
      }
      var current = entry.getValue();
      if (entry.getKey().equals(orderSymbol)) {
        var delta = side == Side.BUY ? orderNotional : orderNotional.negate();
        projected = projected.add(current.add(delta).abs());
      } else {
        projected = projected.add(current.abs());
      }
    }
    if (positionTracker.snapshot().get(orderSymbol) == null) {
      // Order targets a symbol we don't yet hold; only BUYs add new gross exposure.
      if (side == Side.BUY) {
        projected = projected.add(orderNotional);
      }
    }
    return projected;
  }
}
