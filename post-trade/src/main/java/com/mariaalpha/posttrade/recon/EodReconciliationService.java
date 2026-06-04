package com.mariaalpha.posttrade.recon;

import com.mariaalpha.posttrade.entity.ReconciliationBreakEntity;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity.Source;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity.Status;
import com.mariaalpha.posttrade.model.FillForReconRecord;
import com.mariaalpha.posttrade.repository.ReconciliationBreakRepository;
import com.mariaalpha.posttrade.repository.ReconciliationRunRepository;
import com.mariaalpha.posttrade.service.OrderManagerClient;
import com.mariaalpha.posttrade.service.OrderManagerUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Top-level orchestrator for end-of-day reconciliation. Implements the sequence diagram in §2.6.3:
 *
 * <pre>
 *   fetch internal fills(date)
 *   fetch external activities(date) via {@link AlpacaActivitiesClient}
 *   delete prior breaks for date (idempotency §7.3)
 *   compare → persist breaks, publish RECON_BREAK alerts, upsert run record
 * </pre>
 *
 * <p>The run record exists regardless of outcome — it's what tells the UI "ran clean" from "never
 * ran". On exceptions the run is persisted with status=FAILED + error message; this is so that the
 * scheduler doesn't silently re-attempt the same broken run forever.
 */
@Service
public class EodReconciliationService {

  private static final Logger LOG = LoggerFactory.getLogger(EodReconciliationService.class);

  private final OrderManagerClient orderManagerClient;
  private final AlpacaActivitiesClient alpacaClient;
  private final ReconciliationComparator comparator;
  private final ReconciliationBreakRepository breakRepository;
  private final ReconciliationRunRepository runRepository;
  private final RiskAlertPublisher alertPublisher;
  private final ReconMetrics metrics;

  public EodReconciliationService(
      OrderManagerClient orderManagerClient,
      AlpacaActivitiesClient alpacaClient,
      ReconciliationComparator comparator,
      ReconciliationBreakRepository breakRepository,
      ReconciliationRunRepository runRepository,
      RiskAlertPublisher alertPublisher,
      ReconMetrics metrics) {
    this.orderManagerClient = orderManagerClient;
    this.alpacaClient = alpacaClient;
    this.comparator = comparator;
    this.breakRepository = breakRepository;
    this.runRepository = runRepository;
    this.alertPublisher = alertPublisher;
    this.metrics = metrics;
  }

  @Transactional
  public ReconciliationRunEntity runForDate(LocalDate date, Source source) {
    LOG.info("Starting EOD reconciliation for {} (source={})", date, source);
    Instant started = Instant.now();
    ReconciliationRunEntity run = upsertRun(date, source, started);

    try {
      List<FillForReconRecord> internalRaw = orderManagerClient.fetchFillsForDate(date);
      List<InternalFill> internal = toInternal(internalRaw);
      List<ExternalFill> external = alpacaClient.activitiesForDate(date, internal);

      List<ReconciliationBreak> breaks = comparator.compare(date, internal, external);

      // §7.3 idempotency: clear any prior breaks for this date before writing the new ones.
      breakRepository.deleteByReconDate(date);

      List<ReconciliationBreakEntity> entities = new ArrayList<>(breaks.size());
      for (ReconciliationBreak b : breaks) {
        ReconciliationBreakEntity e = toEntity(b);
        entities.add(e);
        metrics.recordBreak(b.breakType().name(), b.severity().name());
      }
      breakRepository.saveAll(entities);
      // Publish alerts after persistence so a Kafka outage can't cause a divergence between the
      // table and the alert stream (Kafka failures log but do not roll back the recon run).
      for (ReconciliationBreak b : breaks) {
        alertPublisher.publishBreak(b);
      }

      run.setStatus(Status.SUCCESS.name());
      run.setInternalFillsCount(internal.size());
      run.setExternalFillsCount(external.size());
      run.setBreaksCount(breaks.size());
      run.setFinishedAt(Instant.now());
      run = runRepository.save(run);
      metrics.recordRun(
          Status.SUCCESS.name(), source.name(), Duration.between(started, run.getFinishedAt()));
      LOG.info(
          "Reconciliation for {} complete: internal={}, external={}, breaks={}",
          date,
          internal.size(),
          external.size(),
          breaks.size());
      return run;
    } catch (AlpacaActivitiesException | OrderManagerUnavailableException e) {
      LOG.error("Reconciliation for {} failed: {}", date, e.getMessage(), e);
      run.setStatus(Status.FAILED.name());
      run.setErrorMessage(truncate(e.getMessage()));
      run.setFinishedAt(Instant.now());
      run = runRepository.save(run);
      metrics.recordRun(
          Status.FAILED.name(), source.name(), Duration.between(started, run.getFinishedAt()));
      return run;
    }
  }

  ReconciliationRunEntity upsertRun(LocalDate date, Source source, Instant started) {
    return runRepository
        .findByReconDate(date)
        .map(
            existing -> {
              existing.setStatus(Status.IN_PROGRESS.name());
              existing.setSource(source.name());
              existing.setStartedAt(started);
              existing.setFinishedAt(null);
              existing.setErrorMessage(null);
              existing.setInternalFillsCount(null);
              existing.setExternalFillsCount(null);
              existing.setBreaksCount(null);
              return runRepository.save(existing);
            })
        .orElseGet(
            () -> {
              ReconciliationRunEntity fresh = new ReconciliationRunEntity();
              fresh.setReconDate(date);
              fresh.setStatus(Status.IN_PROGRESS.name());
              fresh.setSource(source.name());
              fresh.setStartedAt(started);
              return runRepository.save(fresh);
            });
  }

  private static List<InternalFill> toInternal(List<FillForReconRecord> raw) {
    List<InternalFill> out = new ArrayList<>(raw.size());
    for (FillForReconRecord f : raw) {
      out.add(
          new InternalFill(
              f.fillId(),
              f.orderId(),
              f.exchangeOrderId(),
              f.clientOrderId(),
              f.symbol(),
              f.side(),
              f.fillPrice(),
              f.fillQuantity(),
              f.filledAt()));
    }
    return out;
  }

  private static ReconciliationBreakEntity toEntity(ReconciliationBreak b) {
    ReconciliationBreakEntity e = new ReconciliationBreakEntity();
    e.setReconDate(b.reconDate());
    e.setOrderId(b.orderId());
    e.setBreakType(b.breakType().name());
    e.setSeverity(b.severity().name());
    e.setSymbol(b.symbol());
    e.setDescription(b.description());
    e.setInternalQty(b.internalQty());
    e.setExternalQty(b.externalQty());
    e.setInternalPrice(b.internalPrice());
    e.setExternalPrice(b.externalPrice());
    e.setNotional(b.notional());
    return e;
  }

  private static String truncate(String s) {
    if (s == null) {
      return null;
    }
    return s.length() > 1023 ? s.substring(0, 1023) : s;
  }
}
