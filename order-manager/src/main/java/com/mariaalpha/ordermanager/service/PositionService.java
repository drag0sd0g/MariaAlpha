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

  /**
   * Applies a trade fill to the position for its symbol, handling all position accounting in a
   * single transaction with a pessimistic row lock.
   *
   * <p>Three cases are covered:
   *
   * <ul>
   *   <li><b>Flat position</b> — opens a new position at the fill price.
   *   <li><b>Same-direction fill</b> — accumulates quantity and recalculates a weighted average
   *       entry price.
   *   <li><b>Opposite-direction fill</b> — realizes P&L on the closed portion; if the fill
   *       overshoots and flips the position, the new average entry is set to the fill price.
   * </ul>
   *
   * <p>Commission is deducted from realized P&L in all cases. Unrealized P&L is recomputed using
   * the latest mark price from the cache, falling back to the fill price if no tick has been
   * received yet.
   *
   * @param fill the executed fill to apply
   * @return the updated and persisted {@link PositionEntity}
   */
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

  // Lock the row for this symbol to prevent concurrent fill races; create a flat position on first
  // fill
  private PositionEntity resolveOrCreatePosition(String symbol) {
    return positionRepository.findForUpdate(symbol).orElseGet(() -> new PositionEntity(symbol));
  }

  // BUY fills are positive quantity, SELL fills are negative
  private BigDecimal toSignedQuantity(FillEntity fill) {
    return fill.getSide() == Side.BUY ? fill.getFillQuantity() : fill.getFillQuantity().negate();
  }

  // Case 1: no existing holding — open a new position at the fill price
  private void openFreshPosition(
      PositionEntity position, BigDecimal signedFillQty, BigDecimal fillPrice) {
    position.setNetQuantity(signedFillQty);
    position.setAvgEntryPrice(fillPrice);
  }

  // Case 2: same direction — accumulate quantity and recalculate weighted average entry price
  // new_avg = (old_avg × old_qty + fill_price × fill_qty) / new_qty
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

  // Case 3: opposite direction — realize P&L on the closed portion, then update quantity
  // If the fill overshoots (flip), the new avg entry becomes the fill price
  private void closeOrFlipPosition(
      PositionEntity position, BigDecimal signedFillQty, BigDecimal fillPrice) {
    var currentQty = position.getNetQuantity();
    int currentSign = currentQty.signum();
    // Close only up to the existing size; any excess flips the position
    var closingQty = signedFillQty.abs().min(currentQty.abs());
    // P&L per unit: (fill_price - avg_entry) × sign; sign flips the formula correctly for shorts
    var realizedDelta =
        fillPrice
            .subtract(position.getAvgEntryPrice())
            .multiply(BigDecimal.valueOf(currentSign))
            .multiply(closingQty);
    var newQty = currentQty.add(signedFillQty);
    position.setNetQuantity(newQty);
    // Flat: reset avg to zero; flipped: new avg is fill price; partial close: preserve existing avg
    BigDecimal newAvg =
        newQty.signum() == 0
            ? BigDecimal.ZERO
            : (newQty.signum() == currentSign ? position.getAvgEntryPrice() : fillPrice);
    position.setAvgEntryPrice(newAvg);
    position.setRealizedPnl(position.getRealizedPnl().add(realizedDelta));
  }

  // Deduct commission, stamp mark price, and recompute unrealized P&L
  private void applyCommissionAndMark(
      PositionEntity position, String symbol, BigDecimal commission, BigDecimal fillPrice) {
    var realized = position.getRealizedPnl().subtract(commission);
    position.setRealizedPnl(realized.setScale(SCALE, RoundingMode.HALF_UP));
    // Use latest tick from cache; fall back to fill price if no tick has arrived yet
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

  // Unrealized P&L: (mark_price - avg_entry_price) × net_quantity
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
