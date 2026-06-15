package com.mariaalpha.posttrade.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.posttrade.entity.ReconciliationBreakEntity;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity.Source;
import com.mariaalpha.posttrade.entity.ReconciliationRunEntity.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
@DataJpaTest(
    properties = "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.yaml")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ReconciliationRepositoryIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private ReconciliationBreakRepository breakRepository;
  @Autowired private ReconciliationRunRepository runRepository;

  @Test
  void breakWithFullFieldsRoundTrips() {
    var b = new ReconciliationBreakEntity();
    b.setReconDate(LocalDate.of(2026, 6, 1));
    b.setOrderId(UUID.randomUUID());
    b.setBreakType("PRICE_MISMATCH");
    b.setSeverity("HIGH");
    b.setSymbol("AAPL");
    b.setDescription("desc");
    b.setInternalQty(new BigDecimal("100"));
    b.setExternalQty(new BigDecimal("100"));
    b.setInternalPrice(new BigDecimal("180.05"));
    b.setExternalPrice(new BigDecimal("180.10"));
    b.setNotional(new BigDecimal("18005"));
    var saved = breakRepository.save(b);
    assertThat(saved.getBreakId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();

    var found = breakRepository.findByReconDateOrderBySeverityDesc(LocalDate.of(2026, 6, 1));
    assertThat(found).hasSize(1);
    assertThat(found.get(0).getSymbol()).isEqualTo("AAPL");
    assertThat(found.get(0).getInternalPrice()).isEqualByComparingTo("180.05");
  }

  @Test
  void deleteByReconDateClearsRows() {
    breakRepository.save(breakFor(LocalDate.of(2026, 6, 2)));
    breakRepository.save(breakFor(LocalDate.of(2026, 6, 2)));
    breakRepository.save(breakFor(LocalDate.of(2026, 6, 3)));

    long removed = breakRepository.deleteByReconDate(LocalDate.of(2026, 6, 2));
    assertThat(removed).isEqualTo(2);
    assertThat(breakRepository.findByReconDateOrderBySeverityDesc(LocalDate.of(2026, 6, 2)))
        .isEmpty();
    assertThat(breakRepository.findByReconDateOrderBySeverityDesc(LocalDate.of(2026, 6, 3)))
        .hasSize(1);
  }

  @Test
  void missingFillBreakAllowsNullOrderId() {
    var b = new ReconciliationBreakEntity();
    b.setReconDate(LocalDate.of(2026, 6, 4));
    b.setOrderId(null);
    b.setBreakType("MISSING_FILL");
    b.setSeverity("HIGH");
    b.setSymbol("AAPL");
    var saved = breakRepository.save(b);
    assertThat(saved.getBreakId()).isNotNull();
    assertThat(saved.getOrderId()).isNull();
  }

  @Test
  void runRecordUniqueByReconDate() {
    var date = LocalDate.of(2026, 6, 5);
    var r1 = newRun(date);
    runRepository.save(r1);

    var byDate = runRepository.findByReconDate(date);
    assertThat(byDate).isPresent();
    assertThat(byDate.get().getStatus()).isEqualTo(Status.SUCCESS.name());

    var recent = runRepository.findRecent(PageRequest.of(0, 10));
    assertThat(recent).extracting(ReconciliationRunEntity::getReconDate).contains(date);
  }

  private static ReconciliationBreakEntity breakFor(LocalDate date) {
    var b = new ReconciliationBreakEntity();
    b.setReconDate(date);
    b.setOrderId(UUID.randomUUID());
    b.setBreakType("PRICE_MISMATCH");
    b.setSeverity("MEDIUM");
    b.setSymbol("AAPL");
    return b;
  }

  private static ReconciliationRunEntity newRun(LocalDate date) {
    var r = new ReconciliationRunEntity();
    r.setReconDate(date);
    r.setStatus(Status.SUCCESS.name());
    r.setSource(Source.SCHEDULED.name());
    r.setStartedAt(Instant.parse("2026-06-05T22:00:00Z"));
    r.setFinishedAt(Instant.parse("2026-06-05T22:00:05Z"));
    r.setInternalFillsCount(5);
    r.setExternalFillsCount(5);
    r.setBreaksCount(0);
    return r;
  }
}
