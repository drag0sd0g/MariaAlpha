package com.mariaalpha.ordermanager.service;

import com.mariaalpha.ordermanager.config.PortfolioConfig;
import com.mariaalpha.ordermanager.controller.dto.PortfolioSummaryResponse;
import com.mariaalpha.ordermanager.entity.FillEntity;
import com.mariaalpha.ordermanager.entity.PositionEntity;
import com.mariaalpha.ordermanager.model.Side;
import com.mariaalpha.ordermanager.repository.FillRepository;
import com.mariaalpha.ordermanager.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioService {

  private static final int SCALE = 4;

  private final PositionRepository positionRepository;
  private final FillRepository fillRepository;
  private final PortfolioConfig config;

  public PortfolioService(
      PositionRepository positionRepository,
      FillRepository fillRepository,
      PortfolioConfig config) {
    this.positionRepository = positionRepository;
    this.fillRepository = fillRepository;
    this.config = config;
  }

  @Transactional(readOnly = true)
  public BigDecimal netExposure() {
    var total = BigDecimal.ZERO;
    for (var position : positionRepository.findAllOpen()) {
      var mark =
          position.getLastMarkPrice() != null
              ? position.getLastMarkPrice()
              : position.getAvgEntryPrice();
      total = total.add(position.getNetQuantity().multiply(mark));
    }
    return total;
  }

  @Transactional(readOnly = true)
  public BigDecimal grossExposure() {
    var total = BigDecimal.ZERO;
    for (var position : positionRepository.findAllOpen()) {
      var mark =
          position.getLastMarkPrice() != null
              ? position.getLastMarkPrice()
              : position.getAvgEntryPrice();
      total = total.add(position.getNetQuantity().multiply(mark).abs());
    }
    return total;
  }

  @Transactional(readOnly = true)
  public BigDecimal totalPnl() {
    var total = BigDecimal.ZERO;
    for (var position : positionRepository.findAll()) {
      total = total.add(position.getUnrealizedPnl().add(position.getRealizedPnl()));
    }
    return total;
  }

  @Transactional(readOnly = true)
  public PortfolioSummaryResponse summary() {
    List<PositionEntity> positions = positionRepository.findAll();
    List<FillEntity> fills = fillRepository.findAll();
    var initialCash = config.initialCash() != null ? config.initialCash() : BigDecimal.ZERO;
    var cash = computeCashBalance(initialCash, fills);
    var grossExposure = BigDecimal.ZERO;
    var netExposure = BigDecimal.ZERO;
    var realizedPnl = BigDecimal.ZERO;
    var unrealizedPnl = BigDecimal.ZERO;
    int openPositions = 0;

    for (var position : positions) {
      realizedPnl = realizedPnl.add(position.getRealizedPnl());
      unrealizedPnl = unrealizedPnl.add(position.getUnrealizedPnl());
      if (!position.isFlat()) {
        openPositions++;
        var mark =
            position.getLastMarkPrice() != null
                ? position.getLastMarkPrice()
                : position.getAvgEntryPrice();
        var exposure = position.getNetQuantity().multiply(mark);
        grossExposure = grossExposure.add(exposure.abs());
        netExposure = netExposure.add(exposure);
      }
    }

    var totalPnl = realizedPnl.add(unrealizedPnl);
    var totalValue = cash.add(netExposure).add(unrealizedPnl);

    return new PortfolioSummaryResponse(
        totalValue.setScale(SCALE, RoundingMode.HALF_UP),
        cash.setScale(SCALE, RoundingMode.HALF_UP),
        grossExposure.setScale(SCALE, RoundingMode.HALF_UP),
        netExposure.setScale(SCALE, RoundingMode.HALF_UP),
        realizedPnl.setScale(SCALE, RoundingMode.HALF_UP),
        unrealizedPnl.setScale(SCALE, RoundingMode.HALF_UP),
        totalPnl.setScale(SCALE, RoundingMode.HALF_UP),
        openPositions,
        Instant.now());
  }

  BigDecimal computeCashBalance(BigDecimal initialCash, List<FillEntity> fills) {
    var cash = initialCash;
    for (var fill : fills) {
      var notional = fill.getFillPrice().multiply(fill.getFillQuantity());
      cash = fill.getSide() == Side.BUY ? cash.subtract(notional) : cash.add(notional);
      if (fill.getCommission() != null) {
        cash = cash.subtract(fill.getCommission());
      }
    }
    return cash;
  }
}
