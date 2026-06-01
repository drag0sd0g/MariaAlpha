package com.mariaalpha.ordermanager.repository;

import com.mariaalpha.ordermanager.entity.FillEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FillRepository extends JpaRepository<FillEntity, UUID> {

  boolean existsByExchangeFillId(String exchangeFillId);

  List<FillEntity> findByOrder_OrderIdOrderByFilledAtAsc(UUID orderId);

  List<FillEntity> findBySymbolOrderByFilledAtDesc(String symbol);

  /**
   * Fetches fills whose {@code filledAt} falls in {@code [from, to)}. Used by the post-trade EOD
   * reconciliation engine — the JOIN FETCH pulls the parent order eagerly so the response can
   * include {@code clientOrderId} and {@code exchangeOrderId} without a per-fill round trip.
   */
  @Query(
      """
      SELECT f FROM FillEntity f
      JOIN FETCH f.order
      WHERE f.filledAt >= :from AND f.filledAt < :to
      ORDER BY f.filledAt ASC
      """)
  List<FillEntity> findFillsBetween(@Param("from") Instant from, @Param("to") Instant to);
}
