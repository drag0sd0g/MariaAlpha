package com.mariaalpha.posttrade.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One row per EOD reconciliation run (issue 2.6.1). A run record exists regardless of whether
 * breaks were found, so the UI can tell "ran clean" from "never ran". Keyed by {@code reconDate} —
 * re-running for the same date upserts (see §7.3 idempotency).
 */
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRunEntity {

  public enum Status {
    SUCCESS,
    FAILED,
    IN_PROGRESS
  }

  public enum Source {
    SCHEDULED,
    MANUAL
  }

  @Id
  @Column(name = "run_id", nullable = false, updatable = false)
  private UUID runId;

  @Column(name = "recon_date", nullable = false, unique = true)
  private LocalDate reconDate;

  @Column(name = "status", nullable = false, length = 16)
  private String status;

  @Column(name = "source", nullable = false, length = 16)
  private String source;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "internal_fills_count")
  private Integer internalFillsCount;

  @Column(name = "external_fills_count")
  private Integer externalFillsCount;

  @Column(name = "breaks_count")
  private Integer breaksCount;

  @Column(name = "error_message", length = 1024)
  private String errorMessage;

  public ReconciliationRunEntity() {}

  @PrePersist
  void onCreate() {
    if (runId == null) {
      runId = UUID.randomUUID();
    }
  }

  public UUID getRunId() {
    return runId;
  }

  public void setRunId(UUID runId) {
    this.runId = runId;
  }

  public LocalDate getReconDate() {
    return reconDate;
  }

  public void setReconDate(LocalDate reconDate) {
    this.reconDate = reconDate;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(Instant finishedAt) {
    this.finishedAt = finishedAt;
  }

  public Integer getInternalFillsCount() {
    return internalFillsCount;
  }

  public void setInternalFillsCount(Integer internalFillsCount) {
    this.internalFillsCount = internalFillsCount;
  }

  public Integer getExternalFillsCount() {
    return externalFillsCount;
  }

  public void setExternalFillsCount(Integer externalFillsCount) {
    this.externalFillsCount = externalFillsCount;
  }

  public Integer getBreaksCount() {
    return breaksCount;
  }

  public void setBreaksCount(Integer breaksCount) {
    this.breaksCount = breaksCount;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ReconciliationRunEntity that)) {
      return false;
    }
    return Objects.equals(runId, that.runId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(runId);
  }
}
