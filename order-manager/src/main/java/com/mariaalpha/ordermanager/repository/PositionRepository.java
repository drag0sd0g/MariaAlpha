package com.mariaalpha.ordermanager.repository;

import com.mariaalpha.ordermanager.entity.PositionEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PositionRepository extends JpaRepository<PositionEntity, String> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT p FROM PositionEntity p WHERE p.symbol = :symbol")
  Optional<PositionEntity> findForUpdate(@Param("symbol") String symbol);

  @Query("SELECT p FROM PositionEntity p WHERE p.netQuantity <> 0")
  List<PositionEntity> findAllOpen();
}
