package com.mariaalpha.posttrade.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.posttrade.entity.TcaResultEntity;
import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
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
class TcaResultRepositoryIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TcaResultRepository repository;

  @Test
  void saveAndFindByOrderId() {
    TcaResultEntity e = entity(UUID.randomUUID(), "AAPL", "VWAP");
    TcaResultEntity saved = repository.save(e);
    assertThat(saved.getTcaId()).isNotNull();
    assertThat(repository.findByOrderId(e.getOrderId())).isPresent();
    assertThat(repository.existsByOrderId(e.getOrderId())).isTrue();
  }

  @Test
  void uniqueOrderIdConstraintPreventsDuplicates() {
    UUID orderId = UUID.randomUUID();
    repository.save(entity(orderId, "AAPL", "VWAP"));
    org.junit.jupiter.api.Assertions.assertThrows(
        Exception.class, () -> repository.saveAndFlush(entity(orderId, "AAPL", "VWAP")));
  }

  @Test
  void searchFiltersBySymbolAndStrategy() {
    repository.save(entity(UUID.randomUUID(), "AAPL", "VWAP"));
    repository.save(entity(UUID.randomUUID(), "AAPL", "TWAP"));
    repository.save(entity(UUID.randomUUID(), "MSFT", "VWAP"));
    assertThat(repository.search("AAPL", null, PageRequest.of(0, 10))).hasSize(2);
    assertThat(repository.search("AAPL", "VWAP", PageRequest.of(0, 10))).hasSize(1);
    assertThat(repository.search(null, "VWAP", PageRequest.of(0, 10))).hasSize(2);
    assertThat(repository.search(null, null, PageRequest.of(0, 10))).hasSize(3);
  }

  private static TcaResultEntity entity(UUID orderId, String symbol, String strategy) {
    TcaResultEntity e = new TcaResultEntity();
    e.setOrderId(orderId);
    e.setSymbol(symbol);
    e.setStrategy(strategy);
    e.setSide(Side.BUY);
    e.setQuantity(new BigDecimal("1000"));
    e.setSlippageBps(new BigDecimal("2.5"));
    e.setImplShortfallBps(new BigDecimal("3.0"));
    e.setVwapBenchmarkBps(new BigDecimal("1.0"));
    e.setSpreadCostBps(new BigDecimal("1.1"));
    e.setArrivalPrice(new BigDecimal("180.00"));
    e.setRealizedAvgPrice(new BigDecimal("180.05"));
    return e;
  }
}
