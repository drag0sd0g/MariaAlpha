package com.mariaalpha.ordermanager.repository;

import com.mariaalpha.ordermanager.entity.PortfolioSnapshotEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshotEntity, UUID> {}
