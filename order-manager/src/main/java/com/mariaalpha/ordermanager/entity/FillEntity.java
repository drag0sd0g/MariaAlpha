package com.mariaalpha.ordermanager.entity;

import com.mariaalpha.ordermanager.model.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "fills")
public class FillEntity {

  @Id
  @Column(name = "fill_id", nullable = false, updatable = false)
  private UUID fillId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false)
  private OrderEntity order;

  @Column(name = "symbol", nullable = false, length = 16)
  private String symbol;

  @Enumerated(EnumType.STRING)
  @Column(name = "side", nullable = false, length = 4)
  private Side side;

  @Column(name = "fill_price", nullable = false, precision = 18, scale = 8)
  private BigDecimal fillPrice;

  @Column(name = "fill_quantity", nullable = false, precision = 18, scale = 8)
  private BigDecimal fillQuantity;

  @Column(name = "commission", nullable = false, precision = 18, scale = 8)
  private BigDecimal commission = BigDecimal.ZERO;

  @Column(name = "venue", length = 32)
  private String venue;

  @Column(name = "exchange_fill_id", length = 64)
  private String exchangeFillId;

  @Column(name = "filled_at", nullable = false)
  private Instant filledAt;

  public FillEntity() {}

  @PrePersist
  void onCreate() {
    if (fillId == null) {
      fillId = UUID.randomUUID();
    }
    if (filledAt == null) {
      filledAt = Instant.now();
    }
  }

  public UUID getFillId() {
    return fillId;
  }

  public void setFillId(UUID fillId) {
    this.fillId = fillId;
  }

  public OrderEntity getOrder() {
    return order;
  }

  public void setOrder(OrderEntity order) {
    this.order = order;
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

  public BigDecimal getFillPrice() {
    return fillPrice;
  }

  public void setFillPrice(BigDecimal fillPrice) {
    this.fillPrice = fillPrice;
  }

  public BigDecimal getFillQuantity() {
    return fillQuantity;
  }

  public void setFillQuantity(BigDecimal fillQuantity) {
    this.fillQuantity = fillQuantity;
  }

  public BigDecimal getCommission() {
    return commission;
  }

  public void setCommission(BigDecimal commission) {
    this.commission = commission;
  }

  public String getVenue() {
    return venue;
  }

  public void setVenue(String venue) {
    this.venue = venue;
  }

  public String getExchangeFillId() {
    return exchangeFillId;
  }

  public void setExchangeFillId(String exchangeFillId) {
    this.exchangeFillId = exchangeFillId;
  }

  public Instant getFilledAt() {
    return filledAt;
  }

  public void setFilledAt(Instant filledAt) {
    this.filledAt = filledAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FillEntity that)) {
      return false;
    }
    return Objects.equals(fillId, that.fillId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fillId);
  }
}
