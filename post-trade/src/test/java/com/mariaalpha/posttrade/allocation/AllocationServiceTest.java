package com.mariaalpha.posttrade.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.mariaalpha.posttrade.allocation.SubAccountConfig.SubAccount;
import com.mariaalpha.posttrade.entity.AllocationEntity;
import com.mariaalpha.posttrade.model.Side;
import com.mariaalpha.posttrade.repository.AllocationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AllocationServiceTest {

  private SubAccountRegistry registry;
  private AllocationRepository repository;
  private AllocationMetrics metrics;
  private AllocationService service;

  @BeforeEach
  void setUp() {
    var config =
        new SubAccountConfig(
            AllocationMethod.PRO_RATA,
            List.of(
                new SubAccount("HOUSE", 50.0),
                new SubAccount("HF_A", 30.0),
                new SubAccount("HF_B", 20.0)));
    registry = new SubAccountRegistry(config);
    registry.validate();
    repository = Mockito.mock(AllocationRepository.class);
    metrics = new AllocationMetrics(new SimpleMeterRegistry());
    service =
        new AllocationService(registry, new AllocationCalculator(), repository, metrics);
    Mockito.when(repository.saveAll(any())).thenAnswer(inv -> new ArrayList<>(inv.getArgument(0)));
  }

  @Test
  void allocateClearsPriorRowsAndPersistsNew() {
    var orderId = UUID.randomUUID();
    var saved =
        service.allocate(
            new AllocationRequest(
                orderId, "AAPL", Side.BUY, new BigDecimal("1000"), new BigDecimal("178.42"), null));
    assertThat(saved).hasSize(3);
    verify(repository).deleteByOrderId(orderId);

    ArgumentCaptor<List<AllocationEntity>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository).saveAll(captor.capture());
    var persisted = captor.getValue();
    assertThat(persisted).hasSize(3);
    var sum =
        persisted.stream()
            .map(AllocationEntity::getAllocatedQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo("1000");
    assertThat(persisted)
        .allSatisfy(e -> assertThat(e.getOrderId()).isEqualTo(orderId))
        .allSatisfy(e -> assertThat(e.getSymbol()).isEqualTo("AAPL"))
        .allSatisfy(e -> assertThat(e.getSide()).isEqualTo(Side.BUY))
        .allSatisfy(
            e -> assertThat(e.getAllocationMethod()).isEqualTo(AllocationMethod.PRO_RATA))
        .allSatisfy(
            e ->
                assertThat(e.getAllocatedAvgPrice())
                    .isEqualByComparingTo(new BigDecimal("178.42")));
  }

  @Test
  void honoursRequestMethodOverride() {
    var orderId = UUID.randomUUID();
    service.allocate(
        new AllocationRequest(
            orderId,
            "AAPL",
            Side.BUY,
            new BigDecimal("60"),
            new BigDecimal("178.42"),
            AllocationMethod.FIFO));
    ArgumentCaptor<List<AllocationEntity>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .allSatisfy(
            e -> assertThat(e.getAllocationMethod()).isEqualTo(AllocationMethod.FIFO));
  }

  @Test
  void fallsBackToRegistryDefaultMethodWhenNotProvided() {
    var orderId = UUID.randomUUID();
    service.allocate(
        new AllocationRequest(
            orderId, "AAPL", Side.BUY, new BigDecimal("1000"), new BigDecimal("178.42"), null));
    ArgumentCaptor<List<AllocationEntity>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .allSatisfy(
            e -> assertThat(e.getAllocationMethod()).isEqualTo(AllocationMethod.PRO_RATA));
  }

  @Test
  void rejectsUnconfiguredRegistry() {
    var empty = new SubAccountRegistry(new SubAccountConfig(null, null));
    empty.validate();
    var bareService =
        new AllocationService(empty, new AllocationCalculator(), repository, metrics);
    assertThatThrownBy(
            () ->
                bareService.allocate(
                    new AllocationRequest(
                        UUID.randomUUID(),
                        "AAPL",
                        Side.BUY,
                        new BigDecimal("100"),
                        new BigDecimal("178.42"),
                        null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sub-accounts");
  }

  @Test
  void rejectsZeroQuantityRequest() {
    assertThatThrownBy(
            () ->
                service.allocate(
                    new AllocationRequest(
                        UUID.randomUUID(),
                        "AAPL",
                        Side.BUY,
                        BigDecimal.ZERO,
                        new BigDecimal("178.42"),
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parentFilledQuantity");
  }

  @Test
  void rejectsMissingOrderId() {
    assertThatThrownBy(
            () ->
                service.allocate(
                    new AllocationRequest(
                        null,
                        "AAPL",
                        Side.BUY,
                        new BigDecimal("100"),
                        new BigDecimal("178.42"),
                        null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reAllocationIsIdempotentOverOrderId() {
    var orderId = UUID.randomUUID();
    service.allocate(
        new AllocationRequest(
            orderId, "AAPL", Side.BUY, new BigDecimal("1000"), new BigDecimal("178.42"), null));
    service.allocate(
        new AllocationRequest(
            orderId, "AAPL", Side.BUY, new BigDecimal("1200"), new BigDecimal("178.42"), null));
    verify(repository, times(2)).deleteByOrderId(orderId);
    verify(repository, times(2)).saveAll(any());
  }
}
