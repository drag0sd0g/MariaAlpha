package com.mariaalpha.posttrade.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.posttrade.allocation.AllocationMethod;
import com.mariaalpha.posttrade.entity.AllocationEntity;
import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
@DataJpaTest(
    properties = "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.yaml")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AllocationRepositoryIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private AllocationRepository repository;

  @Test
  void saveAndFindByOrderId() {
    UUID orderId = UUID.randomUUID();
    repository.save(entity(orderId, "HOUSE", "AAPL", "500"));
    repository.save(entity(orderId, "HF_A", "AAPL", "300"));
    repository.save(entity(orderId, "HF_B", "AAPL", "200"));

    var fetched = repository.findByOrderIdOrderBySubAccount(orderId);
    assertThat(fetched).hasSize(3);
    assertThat(fetched)
        .extracting(AllocationEntity::getSubAccount)
        .containsExactly("HF_A", "HF_B", "HOUSE");
    assertThat(repository.existsByOrderId(orderId)).isTrue();
  }

  @Test
  void findBySubAccountReturnsAllAllocationsForThatAccount() {
    repository.save(entity(UUID.randomUUID(), "HOUSE", "AAPL", "500"));
    repository.save(entity(UUID.randomUUID(), "HOUSE", "MSFT", "200"));
    repository.save(entity(UUID.randomUUID(), "HF_A", "AAPL", "300"));
    assertThat(repository.findBySubAccountOrderByAllocatedAtDesc("HOUSE")).hasSize(2);
    assertThat(repository.findBySubAccountOrderByAllocatedAtDesc("HF_A")).hasSize(1);
  }

  @Test
  void deleteByOrderIdRemovesAllSubAccountRows() {
    UUID orderId = UUID.randomUUID();
    repository.save(entity(orderId, "HOUSE", "AAPL", "500"));
    repository.save(entity(orderId, "HF_A", "AAPL", "300"));
    repository.deleteByOrderId(orderId);
    assertThat(repository.findByOrderIdOrderBySubAccount(orderId)).isEmpty();
    assertThat(repository.existsByOrderId(orderId)).isFalse();
  }

  @Test
  void uniqueConstraintBlocksDuplicateOrderSubAccount() {
    UUID orderId = UUID.randomUUID();
    repository.save(entity(orderId, "HOUSE", "AAPL", "500"));
    org.junit.jupiter.api.Assertions.assertThrows(
        Exception.class, () -> repository.saveAndFlush(entity(orderId, "HOUSE", "AAPL", "100")));
  }

  private static AllocationEntity entity(
      UUID orderId, String subAccount, String symbol, String quantity) {
    var e = new AllocationEntity();
    e.setOrderId(orderId);
    e.setSubAccount(subAccount);
    e.setSymbol(symbol);
    e.setSide(Side.BUY);
    e.setAllocatedQuantity(new BigDecimal(quantity));
    e.setAllocatedAvgPrice(new BigDecimal("178.42"));
    e.setAllocationMethod(AllocationMethod.PRO_RATA);
    e.setParentFilledQuantity(new BigDecimal("1000"));
    e.setParentAvgPrice(new BigDecimal("178.42"));
    return e;
  }
}
