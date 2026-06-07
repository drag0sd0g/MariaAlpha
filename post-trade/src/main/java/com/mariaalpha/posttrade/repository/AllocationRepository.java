package com.mariaalpha.posttrade.repository;

import com.mariaalpha.posttrade.entity.AllocationEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AllocationRepository extends JpaRepository<AllocationEntity, UUID> {

  List<AllocationEntity> findByOrderIdOrderBySubAccount(UUID orderId);

  List<AllocationEntity> findBySubAccountOrderByAllocatedAtDesc(String subAccount);

  boolean existsByOrderId(UUID orderId);

  void deleteByOrderId(UUID orderId);
}
