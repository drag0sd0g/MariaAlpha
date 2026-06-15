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

  @Query(
      """
      SELECT DISTINCT r.reconDate FROM ReconciliationBreakEntity r
      ORDER BY r.reconDate DESC
      """)
  List<LocalDate> findRecentReconDates();

  @Modifying
  long deleteByReconDate(LocalDate reconDate);
}
