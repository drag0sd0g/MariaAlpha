package com.mariaalpha.posttrade.entity;

import com.mariaalpha.posttrade.allocation.AllocationMethod;
import com.mariaalpha.posttrade.model.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "allocations",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_allocations_order_subaccount",
          columnNames = {"order_id", "sub_account"})
    })
public class AllocationEntity {

  @Id
  @Column(name = "allocation_id", nullable = false, updatable = false)
  private UUID allocationId;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(name = "sub_account", nullable = false, length = 64)
  private String subAccount;

  @Column(name = "symbol", nullable = false, length = 16)
  private String symbol;

  @Enumerated(EnumType.STRING)
  @Column(name = "side", nullable = false, length = 4)
  private Side side;

  @Column(name = "allocated_quantity", nullable = false, precision = 18, scale = 8)
  private BigDecimal allocatedQuantity;

  @Column(name = "allocated_avg_price", nullable = false, precision = 18, scale = 8)
  private BigDecimal allocatedAvgPrice;

  @Enumerated(EnumType.STRING)
  @Column(name = "allocation_method", nullable = false, length = 16)
  private AllocationMethod allocationMethod;

  @Column(name = "parent_filled_quantity", nullable = false, precision = 18, scale = 8)
  private BigDecimal parentFilledQuantity;

  @Column(name = "parent_avg_price", nullable = false, precision = 18, scale = 8)
  private BigDecimal parentAvgPrice;

  @Column(name = "allocated_at", nullable = false)
  private Instant allocatedAt;

  public AllocationEntity() {}

  @PrePersist
  void onCreate() {
    if (allocationId == null) {
      allocationId = UUID.randomUUID();
    }
    if (allocatedAt == null) {
      allocatedAt = Instant.now();
    }
  }

  public UUID getAllocationId() {
    return allocationId;
  }

  public void setAllocationId(UUID allocationId) {
    this.allocationId = allocationId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public void setOrderId(UUID orderId) {
    this.orderId = orderId;
  }

  public String getSubAccount() {
    return subAccount;
  }

  public void setSubAccount(String subAccount) {
    this.subAccount = subAccount;
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

  public BigDecimal getAllocatedQuantity() {
    return allocatedQuantity;
  }

  public void setAllocatedQuantity(BigDecimal allocatedQuantity) {
    this.allocatedQuantity = allocatedQuantity;
  }

  public BigDecimal getAllocatedAvgPrice() {
    return allocatedAvgPrice;
  }

  public void setAllocatedAvgPrice(BigDecimal allocatedAvgPrice) {
    this.allocatedAvgPrice = allocatedAvgPrice;
  }

  public AllocationMethod getAllocationMethod() {
    return allocationMethod;
  }

  public void setAllocationMethod(AllocationMethod allocationMethod) {
    this.allocationMethod = allocationMethod;
  }

  public BigDecimal getParentFilledQuantity() {
    return parentFilledQuantity;
  }

  public void setParentFilledQuantity(BigDecimal v) {
    this.parentFilledQuantity = v;
  }

  public BigDecimal getParentAvgPrice() {
    return parentAvgPrice;
  }

  public void setParentAvgPrice(BigDecimal v) {
    this.parentAvgPrice = v;
  }

  public Instant getAllocatedAt() {
    return allocatedAt;
  }

  public void setAllocatedAt(Instant v) {
    this.allocatedAt = v;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AllocationEntity that)) {
      return false;
    }
    return Objects.equals(allocationId, that.allocationId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(allocationId);
  }
}
