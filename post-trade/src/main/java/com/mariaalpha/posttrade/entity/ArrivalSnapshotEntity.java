package com.mariaalpha.posttrade.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "arrival_snapshots")
public class ArrivalSnapshotEntity {

  @Id
  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "symbol", nullable = false, length = 16)
  private String symbol;

  @Column(name = "arrival_ts", nullable = false)
  private Instant arrivalTs;

  @Column(name = "arrival_mid_price", nullable = false, precision = 18, scale = 8)
  private BigDecimal arrivalMidPrice;

  @Column(name = "arrival_bid_price", precision = 18, scale = 8)
  private BigDecimal arrivalBidPrice;

  @Column(name = "arrival_ask_price", precision = 18, scale = 8)
  private BigDecimal arrivalAskPrice;

  @Column(name = "tick_ts")
  private Instant tickTs;

  @Column(name = "captured_at", nullable = false)
  private Instant capturedAt;

  public ArrivalSnapshotEntity() {}

  @PrePersist
  void onCreate() {
    if (capturedAt == null) {
      capturedAt = Instant.now();
    }
  }

  public UUID getOrderId() {
    return orderId;
  }

  public void setOrderId(UUID orderId) {
    this.orderId = orderId;
  }

  public String getSymbol() {
    return symbol;
  }

  public void setSymbol(String symbol) {
    this.symbol = symbol;
  }

  public Instant getArrivalTs() {
    return arrivalTs;
  }

  public void setArrivalTs(Instant arrivalTs) {
    this.arrivalTs = arrivalTs;
  }

  public BigDecimal getArrivalMidPrice() {
    return arrivalMidPrice;
  }

  public void setArrivalMidPrice(BigDecimal arrivalMidPrice) {
    this.arrivalMidPrice = arrivalMidPrice;
  }

  public BigDecimal getArrivalBidPrice() {
    return arrivalBidPrice;
  }

  public void setArrivalBidPrice(BigDecimal arrivalBidPrice) {
    this.arrivalBidPrice = arrivalBidPrice;
  }

  public BigDecimal getArrivalAskPrice() {
    return arrivalAskPrice;
  }

  public void setArrivalAskPrice(BigDecimal arrivalAskPrice) {
    this.arrivalAskPrice = arrivalAskPrice;
  }

  public Instant getTickTs() {
    return tickTs;
  }

  public void setTickTs(Instant tickTs) {
    this.tickTs = tickTs;
  }

  public Instant getCapturedAt() {
    return capturedAt;
  }

  public void setCapturedAt(Instant capturedAt) {
    this.capturedAt = capturedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ArrivalSnapshotEntity that)) {
      return false;
    }
    return Objects.equals(orderId, that.orderId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(orderId);
  }
}
