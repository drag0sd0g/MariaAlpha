package com.mariaalpha.ordermanager.service;

import com.mariaalpha.ordermanager.config.CurrencyConfig;
import com.mariaalpha.ordermanager.controller.dto.CurrencyExposureResponse;
import com.mariaalpha.ordermanager.entity.PositionEntity;
import com.mariaalpha.ordermanager.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates open-position exposure by currency. Mirrors {@link PortfolioService}'s gross/net
 * exposure logic but groups by the currency resolved from {@link CurrencyConfig}, so a desk
 * trading mixed-currency books can see (for example) JPY exposure separately from USD without
 * needing FX rates to collapse them into a single base.
 *
 * <p>No FX conversion: exposures are reported in their native currency. Adding a rates map and a
 * portfolio-base option is a deliberate follow-up — keeps this ticket small and avoids baking in a
 * choice about which leg of an FX rate the system should consume.
 */
@Service
public class CurrencyExposureService {

  private static final int SCALE = 4;

  private final PositionRepository positionRepository;
  private final CurrencyConfig config;

  public CurrencyExposureService(PositionRepository positionRepository, CurrencyConfig config) {
    this.positionRepository = positionRepository;
    this.config = config;
  }

  @Transactional(readOnly = true)
  public CurrencyExposureResponse exposureByCurrency() {
    List<PositionEntity> positions = positionRepository.findAll();
    Map<String, Aggregator> byCurrency = new HashMap<>();
    int openPositions = 0;

    for (var position : positions) {
      var ccy = config.currencyFor(position.getSymbol());
      var agg = byCurrency.computeIfAbsent(ccy, Aggregator::new);

      // P&L is always aggregated (even for flat positions, realized P&L stays on the books).
      agg.realizedPnl = agg.realizedPnl.add(position.getRealizedPnl());
      agg.unrealizedPnl = agg.unrealizedPnl.add(position.getUnrealizedPnl());

      if (!position.isFlat()) {
        agg.positionCount++;
        openPositions++;
        var mark =
            position.getLastMarkPrice() != null
                ? position.getLastMarkPrice()
                : position.getAvgEntryPrice();
        var exposure = position.getNetQuantity().multiply(mark);
        agg.netExposure = agg.netExposure.add(exposure);
        agg.grossExposure = agg.grossExposure.add(exposure.abs());
      }
    }

    var rows = byCurrency.values().stream().sorted((a, b) -> a.currency.compareTo(b.currency))
        .map(Aggregator::toRow)
        .toList();
    return new CurrencyExposureResponse(rows, openPositions, Instant.now());
  }

  private static final class Aggregator {
    final String currency;
    int positionCount;
    BigDecimal grossExposure = BigDecimal.ZERO;
    BigDecimal netExposure = BigDecimal.ZERO;
    BigDecimal realizedPnl = BigDecimal.ZERO;
    BigDecimal unrealizedPnl = BigDecimal.ZERO;

    Aggregator(String currency) {
      this.currency = currency;
    }

    CurrencyExposureResponse.Row toRow() {
      return new CurrencyExposureResponse.Row(
          currency,
          positionCount,
          grossExposure.setScale(SCALE, RoundingMode.HALF_UP),
          netExposure.setScale(SCALE, RoundingMode.HALF_UP),
          realizedPnl.setScale(SCALE, RoundingMode.HALF_UP),
          unrealizedPnl.setScale(SCALE, RoundingMode.HALF_UP),
          realizedPnl.add(unrealizedPnl).setScale(SCALE, RoundingMode.HALF_UP));
    }
  }
}
