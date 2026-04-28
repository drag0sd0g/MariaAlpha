package com.mariaalpha.posttrade.repository;

import com.mariaalpha.posttrade.entity.TcaResultEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TcaResultRepository extends JpaRepository<TcaResultEntity, UUID> {

  Optional<TcaResultEntity> findByOrderId(UUID orderId);

  boolean existsByOrderId(UUID orderId);

  @Query(
      """
      SELECT r FROM TcaResultEntity r
      WHERE (:symbol IS NULL OR r.symbol = :symbol)
        AND (:strategy IS NULL OR r.strategy = :strategy)
      ORDER BY r.computedAt DESC
      """)
  List<TcaResultEntity> search(
      @Param("symbol") String symbol, @Param("strategy") String strategy, Pageable pageable);
}
