package com.mariaalpha.ordermanager.service;

import com.mariaalpha.ordermanager.entity.FillEntity;
import com.mariaalpha.ordermanager.entity.PositionEntity;
import com.mariaalpha.ordermanager.model.Side;
import com.mariaalpha.ordermanager.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PositionService {

  private static final Logger LOG = LoggerFactory.getLogger(PositionService.class);
  private static final int SCALE = 8;

  private final PositionRepository positionRepository;
  private final Map<String, BigDecimal> markPriceCache = new ConcurrentHashMap<>();

  public PositionService(PositionRepository positionRepository) {
    this.positionRepository = positionRepository;
  }

  @Transactional
  public PositionEntity applyFill(FillEntity fill) {
    var symbol = fill.getSymbol();
    var position = resolveOrCreatePosition(symbol);
    var signedFillQty = toSignedQuantity(fill);
    var fillPrice = fill.getFillPrice();
    var commission = fill.getCommission() != null ? fill.getCommission() : BigDecimal.ZERO;

    int currentSign = position.getNetQuantity().signum();
    int fillSign = signedFillQty.signum();

    if (currentSign == 0) {
      openFreshPosition(position, signedFillQty, fillPrice);
    } else if (currentSign == fillSign) {
      scaleIntoPosition(position, signedFillQty, fillPrice);
    } else {
      closeOrFlipPosition(position, signedFillQty, fillPrice);
    }

    applyCommissionAndMark(position, symbol, commission, fillPrice);

    var saved = positionRepository.save(position);
    LOG.debug(
        "Position {} after fill: qty={} avg={} realized={} unrealized={}",
        symbol,
        saved.getNetQuantity(),
        saved.getAvgEntryPrice(),
        saved.getRealizedPnl(),
        saved.getUnrealizedPnl());
    return saved;
  }

  private PositionEntity resolveOrCreatePosition(String symbol) {
    return positionRepository.findForUpdate(symbol).orElseGet(() -> new PositionEntity(symbol));
  }

  private BigDecimal toSignedQuantity(FillEntity fill) {
    return fill.getSide() == Side.BUY ? fill.getFillQuantity() : fill.getFillQuantity().negate();
  }

  private void openFreshPosition(
      PositionEntity position, BigDecimal signedFillQty, BigDecimal fillPrice) {
    position.setNetQuantity(signedFillQty);
    position.setAvgEntryPrice(fillPrice);
  }

  private void scaleIntoPosition(
      PositionEntity position, BigDecimal signedFillQty, BigDecimal fillPrice) {
    var absCurrentQty = position.getNetQuantity().abs();
    var absFillQty = signedFillQty.abs();
    var newQty = position.getNetQuantity().add(signedFillQty);
    var newAvgPrice =
        position
            .getAvgEntryPrice()
            .multiply(absCurrentQty)
            .add(fillPrice.multiply(absFillQty))
            .divide(newQty.abs(), SCALE, RoundingMode.HALF_UP);
    position.setNetQuantity(newQty);
    position.setAvgEntryPrice(newAvgPrice);
  }

  private void closeOrFlipPosition(
      PositionEntity position, BigDecimal signedFillQty, BigDecimal fillPrice) {
    var currentQty = position.getNetQuantity();
    int currentSign = currentQty.signum();
    var closingQty = signedFillQty.abs().min(currentQty.abs());
    var realizedDelta =
        fillPrice
            .subtract(position.getAvgEntryPrice())
            .multiply(BigDecimal.valueOf(currentSign))
            .multiply(closingQty);
    var newQty = currentQty.add(signedFillQty);
    position.setNetQuantity(newQty);
    BigDecimal newAvg =
        newQty.signum() == 0
            ? BigDecimal.ZERO
            : (newQty.signum() == currentSign ? position.getAvgEntryPrice() : fillPrice);
    position.setAvgEntryPrice(newAvg);
    position.setRealizedPnl(position.getRealizedPnl().add(realizedDelta));
  }

  private void applyCommissionAndMark(
      PositionEntity position, String symbol, BigDecimal commission, BigDecimal fillPrice) {
    var realized = position.getRealizedPnl().subtract(commission);
    position.setRealizedPnl(realized.setScale(SCALE, RoundingMode.HALF_UP));
    var mark = markPriceCache.getOrDefault(symbol, fillPrice);
    position.setLastMarkPrice(mark);
    position.setUnrealizedPnl(computeUnrealized(position, mark));
  }

  @Transactional
  public void markToMarket() {
    for (PositionEntity position : positionRepository.findAllOpen()) {
      var mark = markPriceCache.get(position.getSymbol());
      if (mark == null) {
        continue;
      }
      position.setLastMarkPrice(mark);
      position.setUnrealizedPnl(computeUnrealized(position, mark));
      positionRepository.save(position);
    }
  }

  private BigDecimal computeUnrealized(PositionEntity position, BigDecimal mark) {
    if (position.isFlat() || mark == null) {
      return BigDecimal.ZERO;
    }
    return mark.subtract(position.getAvgEntryPrice())
        .multiply(position.getNetQuantity())
        .setScale(SCALE, RoundingMode.HALF_UP);
  }

  public void updateMarkPrice(String symbol, BigDecimal price) {
    if (symbol != null && price != null && price.signum() > 0) {
      markPriceCache.put(symbol, price);
    }
  }

  public BigDecimal getMarkPrice(String symbol) {
    return markPriceCache.get(symbol);
  }
}
