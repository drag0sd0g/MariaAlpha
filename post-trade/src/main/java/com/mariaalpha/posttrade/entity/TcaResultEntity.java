package com.mariaalpha.posttrade.entity;

import com.mariaalpha.posttrade.model.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "tca_results")
public class TcaResultEntity {

  @Id
  @Column(name = "tca_id", nullable = false, updatable = false)
  private UUID tcaId;

  @Column(name = "order_id", nullable = false, unique = true)
  private UUID orderId;

  @Column(name = "symbol", nullable = false, length = 16)
  private String symbol;

  @Column(name = "strategy", length = 32)
  private String strategy;

  @Enumerated(EnumType.STRING)
  @Column(name = "side", length = 4)
  private Side side;

  @Column(name = "quantity", precision = 18, scale = 8)
  private BigDecimal quantity;

  @Column(name = "slippage_bps", precision = 12, scale = 4)
  private BigDecimal slippageBps;

  @Column(name = "impl_shortfall_bps", precision = 12, scale = 4)
  private BigDecimal implShortfallBps;

  @Column(name = "vwap_benchmark_bps", precision = 12, scale = 4)
  private BigDecimal vwapBenchmarkBps;

  @Column(name = "spread_cost_bps", precision = 12, scale = 4)
  private BigDecimal spreadCostBps;

  @Column(name = "arrival_price", precision = 18, scale = 8)
  private BigDecimal arrivalPrice;

  @Column(name = "arrival_bid_price", precision = 18, scale = 8)
  private BigDecimal arrivalBidPrice;

  @Column(name = "arrival_ask_price", precision = 18, scale = 8)
  private BigDecimal arrivalAskPrice;

  @Column(name = "realized_avg_price", precision = 18, scale = 8)
  private BigDecimal realizedAvgPrice;

  @Column(name = "vwap_benchmark_price", precision = 18, scale = 8)
  private BigDecimal vwapBenchmarkPrice;

  @Column(name = "commission_total", precision = 18, scale = 8)
  private BigDecimal commissionTotal;

  @Column(name = "execution_duration_ms")
  private Long executionDurationMs;

  @Column(name = "computed_at", nullable = false)
  private Instant computedAt;

  public TcaResultEntity() {}

  @PrePersist
  void onCreate() {
    if (tcaId == null) {
      tcaId = UUID.randomUUID();
    }
    if (computedAt == null) {
      computedAt = Instant.now();
    }
  }

  public UUID getTcaId() {
    return tcaId;
  }

  public void setTcaId(UUID tcaId) {
    this.tcaId = tcaId;
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

  public String getStrategy() {
    return strategy;
  }

  public void setStrategy(String strategy) {
    this.strategy = strategy;
  }

  public Side getSide() {
    return side;
  }

  public void setSide(Side side) {
    this.side = side;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public void setQuantity(BigDecimal quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getSlippageBps() {
    return slippageBps;
  }

  public void setSlippageBps(BigDecimal slippageBps) {
    this.slippageBps = slippageBps;
  }

  public BigDecimal getImplShortfallBps() {
    return implShortfallBps;
  }

  public void setImplShortfallBps(BigDecimal v) {
    this.implShortfallBps = v;
  }

  public BigDecimal getVwapBenchmarkBps() {
    return vwapBenchmarkBps;
  }

  public void setVwapBenchmarkBps(BigDecimal v) {
    this.vwapBenchmarkBps = v;
  }

  public BigDecimal getSpreadCostBps() {
    return spreadCostBps;
  }

  public void setSpreadCostBps(BigDecimal v) {
    this.spreadCostBps = v;
  }

  public BigDecimal getArrivalPrice() {
    return arrivalPrice;
  }

  public void setArrivalPrice(BigDecimal v) {
    this.arrivalPrice = v;
  }

  public BigDecimal getArrivalBidPrice() {
    return arrivalBidPrice;
  }

  public void setArrivalBidPrice(BigDecimal v) {
    this.arrivalBidPrice = v;
  }

  public BigDecimal getArrivalAskPrice() {
    return arrivalAskPrice;
  }

  public void setArrivalAskPrice(BigDecimal v) {
    this.arrivalAskPrice = v;
  }

  public BigDecimal getRealizedAvgPrice() {
    return realizedAvgPrice;
  }

  public void setRealizedAvgPrice(BigDecimal v) {
    this.realizedAvgPrice = v;
  }

  public BigDecimal getVwapBenchmarkPrice() {
    return vwapBenchmarkPrice;
  }

  public void setVwapBenchmarkPrice(BigDecimal v) {
    this.vwapBenchmarkPrice = v;
  }

  public BigDecimal getCommissionTotal() {
    return commissionTotal;
  }

  public void setCommissionTotal(BigDecimal v) {
    this.commissionTotal = v;
  }

  public Long getExecutionDurationMs() {
    return executionDurationMs;
  }

  public void setExecutionDurationMs(Long v) {
    this.executionDurationMs = v;
  }

  public Instant getComputedAt() {
    return computedAt;
  }

  public void setComputedAt(Instant v) {
    this.computedAt = v;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TcaResultEntity that)) {
      return false;
    }
    return Objects.equals(tcaId, that.tcaId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(tcaId);
  }
}
