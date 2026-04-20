package com.mariaalpha.ordermanager.entity;

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
@Table(name = "portfolio_snapshots")
public class PortfolioSnapshotEntity {

  @Id
  @Column(name = "snapshot_id", nullable = false, updatable = false)
  private UUID snapshotId;

  @Column(name = "total_value", precision = 18, scale = 4)
  private BigDecimal totalValue;

  @Column(name = "cash_balance", precision = 18, scale = 4)
  private BigDecimal cashBalance;

  @Column(name = "gross_exposure", precision = 18, scale = 4)
  private BigDecimal grossExposure;

  @Column(name = "net_exposure", precision = 18, scale = 4)
  private BigDecimal netExposure;

  @Column(name = "daily_cumulative_pnl", precision = 18, scale = 4)
  private BigDecimal dailyCumulativePnl;

  @Column(name = "open_positions")
  private Integer openPositions;

  @Column(name = "snapshot_at", nullable = false)
  private Instant snapshotAt;

  public PortfolioSnapshotEntity() {}

  @PrePersist
  void onCreate() {
    if (snapshotId == null) {
      snapshotId = UUID.randomUUID();
    }
    if (snapshotAt == null) {
      snapshotAt = Instant.now();
    }
  }

  public UUID getSnapshotId() {
    return snapshotId;
  }

  public void setSnapshotId(UUID snapshotId) {
    this.snapshotId = snapshotId;
  }

  public BigDecimal getTotalValue() {
    return totalValue;
  }

  public void setTotalValue(BigDecimal totalValue) {
    this.totalValue = totalValue;
  }

  public BigDecimal getCashBalance() {
    return cashBalance;
  }

  public void setCashBalance(BigDecimal cashBalance) {
    this.cashBalance = cashBalance;
  }

  public BigDecimal getGrossExposure() {
    return grossExposure;
  }

  public void setGrossExposure(BigDecimal grossExposure) {
    this.grossExposure = grossExposure;
  }

  public BigDecimal getNetExposure() {
    return netExposure;
  }

  public void setNetExposure(BigDecimal netExposure) {
    this.netExposure = netExposure;
  }

  public BigDecimal getDailyCumulativePnl() {
    return dailyCumulativePnl;
  }

  public void setDailyCumulativePnl(BigDecimal dailyCumulativePnl) {
    this.dailyCumulativePnl = dailyCumulativePnl;
  }

  public Integer getOpenPositions() {
    return openPositions;
  }

  public void setOpenPositions(Integer openPositions) {
    this.openPositions = openPositions;
  }

  public Instant getSnapshotAt() {
    return snapshotAt;
  }

  public void setSnapshotAt(Instant snapshotAt) {
    this.snapshotAt = snapshotAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PortfolioSnapshotEntity that)) {
      return false;
    }
    return Objects.equals(snapshotId, that.snapshotId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(snapshotId);
  }
}
