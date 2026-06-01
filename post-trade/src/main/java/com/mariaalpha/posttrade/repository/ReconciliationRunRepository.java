package com.mariaalpha.posttrade.repository;

import com.mariaalpha.posttrade.entity.ReconciliationRunEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRunEntity, UUID> {

  Optional<ReconciliationRunEntity> findByReconDate(LocalDate reconDate);

  @Query("SELECT r FROM ReconciliationRunEntity r ORDER BY r.startedAt DESC")
  List<ReconciliationRunEntity> findRecent(Pageable pageable);
}
