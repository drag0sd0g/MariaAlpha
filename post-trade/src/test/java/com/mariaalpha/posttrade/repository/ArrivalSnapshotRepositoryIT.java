package com.mariaalpha.posttrade.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.posttrade.entity.ArrivalSnapshotEntity;
import java.math.BigDecimal;
import java.time.Instant;
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
class ArrivalSnapshotRepositoryIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private ArrivalSnapshotRepository repository;

  @Test
  void persistAndFind() {
    UUID orderId = UUID.randomUUID();
    ArrivalSnapshotEntity e = new ArrivalSnapshotEntity();
    e.setOrderId(orderId);
    e.setSymbol("AAPL");
    e.setArrivalTs(Instant.now());
    e.setTickTs(Instant.now().minusSeconds(1));
    e.setArrivalMidPrice(new BigDecimal("180.00"));
    e.setArrivalBidPrice(new BigDecimal("179.98"));
    e.setArrivalAskPrice(new BigDecimal("180.02"));
    repository.save(e);
    assertThat(repository.existsByOrderId(orderId)).isTrue();
    assertThat(repository.findById(orderId)).isPresent();
  }
}
