package com.mariaalpha.posttrade.repository;

import com.mariaalpha.posttrade.entity.ArrivalSnapshotEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArrivalSnapshotRepository extends JpaRepository<ArrivalSnapshotEntity, UUID> {

  boolean existsByOrderId(UUID orderId);
}
