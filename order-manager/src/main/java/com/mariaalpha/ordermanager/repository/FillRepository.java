package com.mariaalpha.ordermanager.repository;

import com.mariaalpha.ordermanager.entity.FillEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FillRepository extends JpaRepository<FillEntity, UUID> {

  boolean existsByExchangeFillId(String exchangeFillId);

  List<FillEntity> findByOrder_OrderIdOrderByFilledAtAsc(UUID orderId);

  List<FillEntity> findBySymbolOrderByFilledAtDesc(String symbol);
}
