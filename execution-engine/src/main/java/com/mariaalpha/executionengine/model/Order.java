package com.mariaalpha.executionengine.model;

import static java.math.RoundingMode.HALF_UP;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class Order {

  private final String orderId;
  private final String symbol;
  private final Side side;
  private final int quantity;
  private final OrderType orderType;
  private final BigDecimal limitPrice;
  private final BigDecimal stopPrice;
  private final String strategyName;
  private final Instant createdAt;
  private final AtomicReference<OrderStatus> status;
  private final List<Fill> fills;
  private final Integer displayQuantity;
  private final TimeInForce tif;
  private final String parentOrderId;
  private volatile String exchangeOrderId;
  private volatile int filledQuantity;
  private volatile BigDecimal avgFillPrice;
  private volatile String venue;

  public Order(OrderSignal signal) {
    this.orderId = UUID.randomUUID().toString();
    this.symbol = signal.symbol();
    this.side = signal.side();
    this.quantity = signal.quantity();
    this.orderType = signal.orderType();
    this.limitPrice = signal.limitPrice();
    this.stopPrice = signal.stopPrice();
    this.strategyName = signal.strategyName();
    this.createdAt = Instant.now();
    this.status = new AtomicReference<>(OrderStatus.NEW);
    this.fills = new ArrayList<>();
    this.filledQuantity = 0;
    this.avgFillPrice = BigDecimal.ZERO;
    this.displayQuantity = signal.displayQuantity();
    this.tif = signal.tif();
    this.parentOrderId = signal.parentOrderId();
  }

  public String getOrderId() {
    return orderId;
  }

  public String getSymbol() {
    return symbol;
  }

  public Side getSide() {
    return side;
  }

  public int getQuantity() {
    return quantity;
  }

  public OrderType getOrderType() {
    return orderType;
  }

  public BigDecimal getLimitPrice() {
    return limitPrice;
  }

  public BigDecimal getStopPrice() {
    return stopPrice;
  }

  public String getStrategyName() {
    return strategyName;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public OrderStatus getStatus() {
    return status.get();
  }

  public String getExchangeOrderId() {
    return exchangeOrderId;
  }

  public void setExchangeOrderId(String exchangeOrderId) {
    this.exchangeOrderId = exchangeOrderId;
  }

  public int getFilledQuantity() {
    return filledQuantity;
  }

  public boolean compareAndSetStatus(OrderStatus expected, OrderStatus newStatus) {
    return status.compareAndSet(expected, newStatus);
  }

  public int getRemainingQuantity() {
    return getQuantity() - getFilledQuantity();
  }

  public BigDecimal getAvgFillPrice() {
    return avgFillPrice;
  }

  public List<Fill> getFills() {
    return List.copyOf(fills);
  }

  public String getVenue() {
    return venue;
  }

  public void setVenue(String venue) {
    this.venue = venue;
  }

  public Integer getDisplayQuantity() {
    return displayQuantity;
  }

  public TimeInForce getTif() {
    return tif;
  }

  public String getParentOrderId() {
    return parentOrderId;
  }

  public OrderSnapshot toSnapshot() {
    return new OrderSnapshot(
        orderId,
        orderId,
        symbol,
        side,
        quantity,
        orderType,
        limitPrice,
        stopPrice,
        strategyName,
        filledQuantity,
        avgFillPrice,
        exchangeOrderId,
        venue,
        displayQuantity,
        tif,
        parentOrderId);
  }

  public synchronized void addFill(Fill fill) {
    fills.add(fill);
    var newFilledQuantity = getFilledQuantity() + fill.fillQuantity();
    var totalCost =
        avgFillPrice
            .multiply(BigDecimal.valueOf(filledQuantity))
            .add(fill.fillPrice().multiply(BigDecimal.valueOf(fill.fillQuantity())));
    this.filledQuantity = newFilledQuantity;
    this.avgFillPrice =
        filledQuantity > 0
            ? totalCost.divide(BigDecimal.valueOf(filledQuantity), 6, HALF_UP)
            : BigDecimal.ZERO;
  }
}
