package com.mariaalpha.posttrade.allocation;

import com.mariaalpha.posttrade.entity.AllocationEntity;
import com.mariaalpha.posttrade.repository.AllocationRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the allocation flow: pick the method (request override → registry default), run the
 * pure {@link AllocationCalculator}, persist the {@link AllocationEntity} rows, emit metrics.
 *
 * <p>Re-running allocation for the same {@code orderId} is idempotent: the prior rows are deleted
 * before the new ones are written, so re-allocating with a different method or after a re-fill is
 * safe. Tests assert this round-trip.
 */
@Service
public class AllocationService {

  private static final Logger LOG = LoggerFactory.getLogger(AllocationService.class);

  private final SubAccountRegistry registry;
  private final AllocationCalculator calculator;
  private final AllocationRepository repository;
  private final AllocationMetrics metrics;

  public AllocationService(
      SubAccountRegistry registry,
      AllocationCalculator calculator,
      AllocationRepository repository,
      AllocationMetrics metrics) {
    this.registry = registry;
    this.calculator = calculator;
    this.repository = repository;
    this.metrics = metrics;
  }

  /**
   * Allocate a parent order's fills across sub-accounts and persist the result. Returns the
   * persisted rows in sub-account name order.
   *
   * @throws IllegalStateException if the sub-account registry is empty (no accounts configured)
   * @throws IllegalArgumentException if {@code request} carries invalid quantity / price
   */
  @Transactional
  public List<AllocationEntity> allocate(AllocationRequest request) {
    if (!registry.isConfigured()) {
      throw new IllegalStateException(
          "No sub-accounts configured under post-trade.allocation.sub-accounts");
    }
    if (request.orderId() == null) {
      throw new IllegalArgumentException("orderId is required");
    }
    if (request.symbol() == null || request.symbol().isBlank()) {
      throw new IllegalArgumentException("symbol is required");
    }
    if (request.side() == null) {
      throw new IllegalArgumentException("side is required");
    }
    if (request.parentFilledQuantity() == null || request.parentFilledQuantity().signum() <= 0) {
      throw new IllegalArgumentException("parentFilledQuantity must be > 0");
    }

    var method = request.method() != null ? request.method() : registry.defaultMethod();
    var results =
        calculator.allocate(
            registry.accounts(), method, request.parentFilledQuantity(), request.parentAvgPrice());
    if (results.isEmpty()) {
      return List.of();
    }

    // Idempotent re-allocation: clear any prior rows for this parent before persisting.
    // Force-flush the delete before the saveAll. Without this, Hibernate may batch the insert
    // ahead of the delete in the action queue and trip the UNIQUE(order_id, sub_account) constraint
    // when re-allocating the same parent — even though both ops are in the same @Transactional
    // block. Manifested as a 500 on the e2e idempotency test.
    repository.deleteByOrderId(request.orderId());
    repository.flush();

    var entities = results.stream().map(r -> toEntity(request, r)).toList();
    var saved = repository.saveAll(entities);

    double totalShares =
        saved.stream().mapToDouble(e -> e.getAllocatedQuantity().doubleValue()).sum();
    metrics.recordRun(request.symbol(), method, saved.size(), totalShares);
    LOG.info(
        "Allocated order {} ({} shares × ${}) across {} sub-accounts via {}",
        request.orderId(),
        request.parentFilledQuantity().toPlainString(),
        request.parentAvgPrice().toPlainString(),
        saved.size(),
        method);
    return saved;
  }

  private static AllocationEntity toEntity(AllocationRequest request, AllocationResult result) {
    var e = new AllocationEntity();
    e.setOrderId(request.orderId());
    e.setSubAccount(result.subAccount());
    e.setSymbol(request.symbol());
    e.setSide(request.side());
    e.setAllocatedQuantity(result.allocatedQuantity());
    e.setAllocatedAvgPrice(result.allocatedAvgPrice());
    e.setAllocationMethod(result.method());
    e.setParentFilledQuantity(request.parentFilledQuantity());
    e.setParentAvgPrice(request.parentAvgPrice());
    return e;
  }
}
