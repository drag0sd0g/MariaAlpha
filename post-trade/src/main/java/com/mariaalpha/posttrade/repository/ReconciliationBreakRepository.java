package com.mariaalpha.posttrade.repository;

import com.mariaalpha.posttrade.entity.ReconciliationBreakEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ReconciliationBreakRepository
    extends JpaRepository<ReconciliationBreakEntity, UUID> {

  List<ReconciliationBreakEntity> findByReconDateOrderBySeverityDesc(LocalDate reconDate);

  List<ReconciliationBreakEntity> findByOrderIdOrderByReconDateDesc(UUID orderId);

  /**
   * Returns the distinct reconciliation dates that produced at least one break, newest first. Used
   * by the UI to populate the "recent runs" picker.
   */
  @Query(
      """
      SELECT DISTINCT r.reconDate FROM ReconciliationBreakEntity r
      ORDER BY r.reconDate DESC
      """)
  List<LocalDate> findRecentReconDates();

  /**
   * Idempotency support — recon results are keyed by {@code reconDate} (§7.3): re-running for the
   * same date wipes prior breaks before writing new ones, so a partial earlier run can't leave
   * stale rows.
   */
  @Modifying
  long deleteByReconDate(LocalDate reconDate);
}
