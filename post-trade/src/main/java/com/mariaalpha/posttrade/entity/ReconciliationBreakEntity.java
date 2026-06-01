package com.mariaalpha.posttrade.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Row from the {@code reconciliation_breaks} table populated by the EOD reconciliation engine
 * (issue 2.6.1). One row per discrepancy detected between internal fills and the external venue's
 * day-of-trade activity report. Exposed for the UI Reconciliation page (issue 2.5.4) via {@link
 * com.mariaalpha.posttrade.controller.ReconController}.
 *
 * <p>{@code internalQty}/{@code externalQty}/{@code internalPrice}/{@code externalPrice} are
 * populated for {@code QUANTITY_MISMATCH} / {@code PRICE_MISMATCH} types. For {@code MISSING_FILL}
 * the external side is populated and the internal side is null; for {@code EXTRA_FILL} it's the
 * other way round.
 */
@Entity
@Table(name = "reconciliation_breaks")
public class ReconciliationBreakEntity {

  @Id
  @Column(name = "break_id", nullable = false, updatable = false)
  private UUID breakId;

  @Column(name = "recon_date", nullable = false)
  private LocalDate reconDate;

  @Column(name = "order_id")
  private UUID orderId;

  @Column(name = "break_type", nullable = false, length = 32)
  private String breakType;

  @Column(name = "severity", nullable = false, length = 16)
  private String severity;

  @Column(name = "resolution", length = 32)
  private String resolution;

  @Column(name = "symbol", length = 16)
  private String symbol;

  @Column(name = "description", length = 512)
  private String description;

  @Column(name = "internal_qty", precision = 18, scale = 8)
  private BigDecimal internalQty;

  @Column(name = "external_qty", precision = 18, scale = 8)
  private BigDecimal externalQty;

  @Column(name = "internal_price", precision = 18, scale = 8)
  private BigDecimal internalPrice;

  @Column(name = "external_price", precision = 18, scale = 8)
  private BigDecimal externalPrice;

  @Column(name = "notional", precision = 18, scale = 8)
  private BigDecimal notional;

  @Column(name = "created_at")
  private Instant createdAt;

  public ReconciliationBreakEntity() {}

  @PrePersist
  void onCreate() {
    if (breakId == null) {
      breakId = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
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

  public String getSymbol() {
    return symbol;
  }

  public void setSymbol(String symbol) {
    this.symbol = symbol;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public BigDecimal getInternalQty() {
    return internalQty;
  }

  public void setInternalQty(BigDecimal internalQty) {
    this.internalQty = internalQty;
  }

  public BigDecimal getExternalQty() {
    return externalQty;
  }

  public void setExternalQty(BigDecimal externalQty) {
    this.externalQty = externalQty;
  }

  public BigDecimal getInternalPrice() {
    return internalPrice;
  }

  public void setInternalPrice(BigDecimal internalPrice) {
    this.internalPrice = internalPrice;
  }

  public BigDecimal getExternalPrice() {
    return externalPrice;
  }

  public void setExternalPrice(BigDecimal externalPrice) {
    this.externalPrice = externalPrice;
  }

  public BigDecimal getNotional() {
    return notional;
  }

  public void setNotional(BigDecimal notional) {
    this.notional = notional;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
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
