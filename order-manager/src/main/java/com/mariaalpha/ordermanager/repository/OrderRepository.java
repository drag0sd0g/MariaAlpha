package com.mariaalpha.ordermanager.repository;

import com.mariaalpha.ordermanager.entity.OrderEntity;
import com.mariaalpha.ordermanager.model.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

  Optional<OrderEntity> findByClientOrderId(String clientOrderId);

  Optional<OrderEntity> findByExchangeOrderId(String exchangeOrderId);

  boolean existsByClientOrderId(String clientOrderId);

  @Query(
      """
      SELECT o FROM OrderEntity o
      WHERE (:symbol IS NULL OR o.symbol = :symbol)
        AND (:status IS NULL OR o.status = :status)
        AND (:strategy IS NULL OR o.strategy = :strategy)
        AND (CAST(:from AS timestamp) IS NULL OR o.createdAt >= :from)
        AND (CAST(:to AS timestamp) IS NULL OR o.createdAt <= :to)
      ORDER BY o.createdAt DESC
      """)
  List<OrderEntity> search(
      @Param("symbol") String symbol,
      @Param("status") OrderStatus status,
      @Param("strategy") String strategy,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);
}
