package com.mariaalpha.ordermanager.entity;

import com.mariaalpha.ordermanager.model.OrderStatus;
import com.mariaalpha.ordermanager.model.OrderType;
import com.mariaalpha.ordermanager.model.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class OrderEntity {

  @Id
  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "client_order_id", nullable = false, unique = true, length = 64)
  private String clientOrderId;

  @Column(name = "symbol", nullable = false, length = 16)
  private String symbol;

  @Enumerated(EnumType.STRING)
  @Column(name = "side", nullable = false, length = 4)
  private Side side;

  @Enumerated(EnumType.STRING)
  @Column(name = "order_type", nullable = false, length = 16)
  private OrderType orderType;

  @Column(name = "quantity", nullable = false, precision = 18, scale = 8)
  private BigDecimal quantity;

  @Column(name = "limit_price", precision = 18, scale = 8)
  private BigDecimal limitPrice;

  @Column(name = "stop_price", precision = 18, scale = 8)
  private BigDecimal stopPrice;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private OrderStatus status;

  @Column(name = "strategy", length = 32)
  private String strategy;

  @Column(name = "filled_quantity", nullable = false, precision = 18, scale = 8)
  private BigDecimal filledQuantity = BigDecimal.ZERO;

  @Column(name = "avg_fill_price", precision = 18, scale = 8)
  private BigDecimal avgFillPrice;

  @Column(name = "exchange_order_id", length = 64)
  private String exchangeOrderId;

  @Column(name = "venue", length = 32)
  private String venue;

  @Column(name = "display_quantity", precision = 18, scale = 8)
  private BigDecimal displayQuantity;

  @Column(name = "arrival_mid_price", precision = 18, scale = 8)
  private BigDecimal arrivalMidPrice;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public OrderEntity() {}

  @PrePersist
  void onCreate() {
    if (orderId == null) {
      orderId = UUID.randomUUID();
    }
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getOrderId() {
    return orderId;
  }

  public void setOrderId(UUID orderId) {
    this.orderId = orderId;
  }

  public String getClientOrderId() {
    return clientOrderId;
  }

  public void setClientOrderId(String clientOrderId) {
    this.clientOrderId = clientOrderId;
  }

  public String getSymbol() {
    return symbol;
  }

  public void setSymbol(String symbol) {
    this.symbol = symbol;
  }

  public Side getSide() {
    return side;
  }

  public void setSide(Side side) {
    this.side = side;
  }

  public OrderType getOrderType() {
    return orderType;
  }

  public void setOrderType(OrderType orderType) {
    this.orderType = orderType;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public void setQuantity(BigDecimal quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getLimitPrice() {
    return limitPrice;
  }

  public void setLimitPrice(BigDecimal limitPrice) {
    this.limitPrice = limitPrice;
  }

  public BigDecimal getStopPrice() {
    return stopPrice;
  }

  public void setStopPrice(BigDecimal stopPrice) {
    this.stopPrice = stopPrice;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public void setStatus(OrderStatus status) {
    this.status = status;
  }

  public String getStrategy() {
    return strategy;
  }

  public void setStrategy(String strategy) {
    this.strategy = strategy;
  }

  public BigDecimal getFilledQuantity() {
    return filledQuantity;
  }

  public void setFilledQuantity(BigDecimal filledQuantity) {
    this.filledQuantity = filledQuantity;
  }

  public BigDecimal getAvgFillPrice() {
    return avgFillPrice;
  }

  public void setAvgFillPrice(BigDecimal avgFillPrice) {
    this.avgFillPrice = avgFillPrice;
  }

  public String getExchangeOrderId() {
    return exchangeOrderId;
  }

  public void setExchangeOrderId(String exchangeOrderId) {
    this.exchangeOrderId = exchangeOrderId;
  }

  public String getVenue() {
    return venue;
  }

  public void setVenue(String venue) {
    this.venue = venue;
  }

  public BigDecimal getDisplayQuantity() {
    return displayQuantity;
  }

  public void setDisplayQuantity(BigDecimal displayQuantity) {
    this.displayQuantity = displayQuantity;
  }

  public BigDecimal getArrivalMidPrice() {
    return arrivalMidPrice;
  }

  public void setArrivalMidPrice(BigDecimal arrivalMidPrice) {
    this.arrivalMidPrice = arrivalMidPrice;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof OrderEntity that)) {
      return false;
    }
    return Objects.equals(orderId, that.orderId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(orderId);
  }
}
