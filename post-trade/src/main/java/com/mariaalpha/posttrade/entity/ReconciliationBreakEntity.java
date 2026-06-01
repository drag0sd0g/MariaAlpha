package com.mariaalpha.posttrade.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Row from the {@code reconciliation_breaks} table populated by the EOD reconciliation engine
 * (issue 2.6.1, scheduled for a later iteration). Exposed for the UI Reconciliation page (issue
 * 2.5.4) via {@link com.mariaalpha.posttrade.controller.ReconController}; until 2.6.1 lands the
 * table is empty and the endpoint returns an empty list.
 */
@Entity
@Table(name = "reconciliation_breaks")
public class ReconciliationBreakEntity {

  @Id
  @Column(name = "break_id", nullable = false, updatable = false)
  private UUID breakId;

  @Column(name = "recon_date", nullable = false)
  private LocalDate reconDate;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(name = "break_type", nullable = false, length = 32)
  private String breakType;

  @Column(name = "severity", nullable = false, length = 16)
  private String severity;

  @Column(name = "resolution", length = 32)
  private String resolution;

  public ReconciliationBreakEntity() {}

  @PrePersist
  void onCreate() {
    if (breakId == null) {
      breakId = UUID.randomUUID();
    }
  }

  public UUID getBreakId() {
    return breakId;
  }

  public void setBreakId(UUID breakId) {
    this.breakId = breakId;
  }

  public LocalDate getReconDate() {
    return reconDate;
  }

  public void setReconDate(LocalDate reconDate) {
    this.reconDate = reconDate;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public void setOrderId(UUID orderId) {
    this.orderId = orderId;
  }

  public String getBreakType() {
    return breakType;
  }

  public void setBreakType(String breakType) {
    this.breakType = breakType;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String severity) {
    this.severity = severity;
  }

  public String getResolution() {
    return resolution;
  }

  public void setResolution(String resolution) {
    this.resolution = resolution;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ReconciliationBreakEntity that)) {
      return false;
    }
    return Objects.equals(breakId, that.breakId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(breakId);
  }
}
