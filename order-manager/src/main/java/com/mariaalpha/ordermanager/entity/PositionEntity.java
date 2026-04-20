package com.mariaalpha.ordermanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "positions")
public class PositionEntity {

  @Id
  @Column(name = "symbol", nullable = false, length = 16)
  private String symbol;

  @Column(name = "net_quantity", nullable = false, precision = 18, scale = 8)
  private BigDecimal netQuantity = BigDecimal.ZERO;

  @Column(name = "avg_entry_price", nullable = false, precision = 18, scale = 8)
  private BigDecimal avgEntryPrice = BigDecimal.ZERO;

  @Column(name = "realized_pnl", nullable = false, precision = 18, scale = 8)
  private BigDecimal realizedPnl = BigDecimal.ZERO;

  @Column(name = "unrealized_pnl", nullable = false, precision = 18, scale = 8)
  private BigDecimal unrealizedPnl = BigDecimal.ZERO;

  @Column(name = "last_mark_price", precision = 18, scale = 8)
  private BigDecimal lastMarkPrice;

  @Column(name = "sector", length = 32)
  private String sector;

  @Column(name = "beta", precision = 8, scale = 4)
  private BigDecimal beta;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public PositionEntity() {}

  public PositionEntity(String symbol) {
    this.symbol = symbol;
  }

  @PrePersist
  @PreUpdate
  void touch() {
    updatedAt = Instant.now();
  }

  public boolean isFlat() {
    return netQuantity.signum() == 0;
  }

  public boolean isLong() {
    return netQuantity.signum() > 0;
  }

  public String getSymbol() {
    return symbol;
  }

  public void setSymbol(String symbol) {
    this.symbol = symbol;
  }

  public BigDecimal getNetQuantity() {
    return netQuantity;
  }

  public void setNetQuantity(BigDecimal netQuantity) {
    this.netQuantity = netQuantity;
  }

  public BigDecimal getAvgEntryPrice() {
    return avgEntryPrice;
  }

  public void setAvgEntryPrice(BigDecimal avgEntryPrice) {
    this.avgEntryPrice = avgEntryPrice;
  }

  public BigDecimal getRealizedPnl() {
    return realizedPnl;
  }

  public void setRealizedPnl(BigDecimal realizedPnl) {
    this.realizedPnl = realizedPnl;
  }

  public BigDecimal getUnrealizedPnl() {
    return unrealizedPnl;
  }

  public void setUnrealizedPnl(BigDecimal unrealizedPnl) {
    this.unrealizedPnl = unrealizedPnl;
  }

  public BigDecimal getLastMarkPrice() {
    return lastMarkPrice;
  }

  public void setLastMarkPrice(BigDecimal lastMarkPrice) {
    this.lastMarkPrice = lastMarkPrice;
  }

  public String getSector() {
    return sector;
  }

  public void setSector(String sector) {
    this.sector = sector;
  }

  public BigDecimal getBeta() {
    return beta;
  }

  public void setBeta(BigDecimal beta) {
    this.beta = beta;
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
    if (!(o instanceof PositionEntity that)) {
      return false;
    }
    return Objects.equals(symbol, that.symbol);
  }

  @Override
  public int hashCode() {
    return Objects.hash(symbol);
  }
}
