package com.mariaalpha.posttrade.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.posttrade.entity.ReconciliationBreakEntity;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity.Source;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity.Status;
import com.mariaalpha.posttrade.model.FillForReconRecord;
import com.mariaalpha.posttrade.model.Side;
import com.mariaalpha.posttrade.repository.ReconciliationBreakRepository;
import com.mariaalpha.posttrade.repository.ReconciliationRunRepository;
import com.mariaalpha.posttrade.service.OrderManagerClient;
import com.mariaalpha.posttrade.service.OrderManagerUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class EodReconciliationServiceTest {

  private OrderManagerClient orderManagerClient;
  private AlpacaActivitiesClient alpacaClient;
  private ReconciliationComparator comparator;
  private ReconciliationBreakRepository breakRepository;
  private ReconciliationRunRepository runRepository;
  private RiskAlertPublisher alertPublisher;
  private ReconMetrics metrics;
  private EodReconciliationService service;

  @BeforeEach
  void setUp() {
    orderManagerClient = Mockito.mock(OrderManagerClient.class);
    alpacaClient = Mockito.mock(AlpacaActivitiesClient.class);
    breakRepository = Mockito.mock(ReconciliationBreakRepository.class);
    runRepository = Mockito.mock(ReconciliationRunRepository.class);
    alertPublisher = Mockito.mock(RiskAlertPublisher.class);
    metrics = new ReconMetrics(new SimpleMeterRegistry());
    comparator =
        new ReconciliationComparator(
            new BigDecimal("1.0"),
            new BigDecimal("0.001"),
            new BigDecimal("10000"),
            new BigDecimal("100000"));

    when(runRepository.findByReconDate(any())).thenReturn(Optional.empty());
    when(runRepository.save(any()))
        .thenAnswer(inv -> inv.getArgument(0, ReconciliationRunEntity.class));
    when(breakRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0, List.class));

    service =
        new EodReconciliationService(
            orderManagerClient,
            alpacaClient,
            comparator,
            breakRepository,
            runRepository,
            alertPublisher,
            metrics);
  }

  @Test
  void mirroredInternalAndExternalRunsClean() {
    LocalDate d = LocalDate.of(2026, 6, 1);
    var fill = stubInternalFill("alpaca-1", "AAPL");
    when(orderManagerClient.fetchFillsForDate(d)).thenReturn(List.of(fill));
    when(alpacaClient.activitiesForDate(eq(d), anyList()))
        .thenAnswer(
            inv -> {
              List<InternalFill> in = inv.getArgument(1);
              return in.stream()
                  .map(
                      f ->
                          new ExternalFill(
                              f.fillId().toString(),
                              f.exchangeOrderId(),
                              f.clientOrderId(),
                              f.symbol(),
                              f.side(),
                              f.price(),
                              f.quantity(),
                              f.filledAt()))
                  .toList();
            });

    var run = service.runForDate(d, Source.MANUAL);

    assertThat(run.getStatus()).isEqualTo(Status.SUCCESS.name());
    assertThat(run.getInternalFillsCount()).isEqualTo(1);
    assertThat(run.getExternalFillsCount()).isEqualTo(1);
    assertThat(run.getBreaksCount()).isEqualTo(0);
    verify(breakRepository).deleteByReconDate(d);
    verify(breakRepository).saveAll(List.of());
    verify(alertPublisher, never()).publishBreak(any());
  }

  @Test
  void mismatchedExternalProducesBreaksAndAlerts() {
    LocalDate d = LocalDate.of(2026, 6, 1);
    var internal = stubInternalFill("alpaca-1", "AAPL");
    when(orderManagerClient.fetchFillsForDate(d)).thenReturn(List.of(internal));
    when(alpacaClient.activitiesForDate(eq(d), anyList()))
        .thenReturn(
            List.of(
                new ExternalFill(
                    "act-1",
                    "alpaca-1",
                    UUID.randomUUID().toString(),
                    "AAPL",
                    Side.BUY,
                    new BigDecimal("180.05"),
                    new BigDecimal("80"),
                    Instant.parse("2026-06-01T15:30:00Z"))));

    var run = service.runForDate(d, Source.MANUAL);

    assertThat(run.getStatus()).isEqualTo(Status.SUCCESS.name());
    assertThat(run.getBreaksCount()).isEqualTo(1);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ReconciliationBreakEntity>> cap = ArgumentCaptor.forClass(List.class);
    verify(breakRepository).saveAll(cap.capture());
    assertThat(cap.getValue()).hasSize(1);
    assertThat(cap.getValue().get(0).getBreakType()).isEqualTo("QUANTITY_MISMATCH");

    verify(alertPublisher, times(1)).publishBreak(any());
  }

  @Test
  void alpacaFailureMarksRunFailedWithoutDeletingPriorBreaks() {
    LocalDate d = LocalDate.of(2026, 6, 1);
    when(orderManagerClient.fetchFillsForDate(d)).thenReturn(List.of());
    when(alpacaClient.activitiesForDate(any(), any()))
        .thenThrow(new AlpacaActivitiesException("HTTP 503"));

    var run = service.runForDate(d, Source.SCHEDULED);

    assertThat(run.getStatus()).isEqualTo(Status.FAILED.name());
    assertThat(run.getErrorMessage()).contains("HTTP 503");
    verify(breakRepository, never()).deleteByReconDate(any());
    verify(breakRepository, never()).saveAll(anyList());
    verify(alertPublisher, never()).publishBreak(any());
  }

  @Test
  void orderManagerFailureMarksRunFailed() {
    LocalDate d = LocalDate.of(2026, 6, 1);
    when(orderManagerClient.fetchFillsForDate(d))
        .thenThrow(new OrderManagerUnavailableException("down", new RuntimeException()));

    var run = service.runForDate(d, Source.SCHEDULED);

    assertThat(run.getStatus()).isEqualTo(Status.FAILED.name());
    verify(alpacaClient, never()).activitiesForDate(any(), any());
  }

  @Test
  void rerunningSameDateUpsertsExistingRunRecord() {
    LocalDate d = LocalDate.of(2026, 6, 1);
    var existing = new ReconciliationRunEntity();
    existing.setRunId(UUID.randomUUID());
    existing.setReconDate(d);
    existing.setStatus(Status.SUCCESS.name());
    when(runRepository.findByReconDate(d)).thenReturn(Optional.of(existing));
    when(orderManagerClient.fetchFillsForDate(d)).thenReturn(List.of());
    when(alpacaClient.activitiesForDate(eq(d), anyList())).thenReturn(List.of());

    var run = service.runForDate(d, Source.MANUAL);
    assertThat(run.getRunId()).isEqualTo(existing.getRunId());
    assertThat(run.getStatus()).isEqualTo(Status.SUCCESS.name());
  }

  private static FillForReconRecord stubInternalFill(String exchangeOrderId, String symbol) {
    return new FillForReconRecord(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        exchangeOrderId,
        symbol,
        Side.BUY,
        new BigDecimal("180.05"),
        new BigDecimal("100"),
        BigDecimal.ZERO,
        "PRIMARY",
        "fill-1",
        Instant.parse("2026-06-01T15:30:00Z"));
  }
}
