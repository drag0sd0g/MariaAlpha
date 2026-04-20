package com.mariaalpha.ordermanager.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.ordermanager.entity.FillEntity;
import com.mariaalpha.ordermanager.entity.OrderEntity;
import com.mariaalpha.ordermanager.model.OrderStatus;
import com.mariaalpha.ordermanager.model.OrderType;
import com.mariaalpha.ordermanager.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class FillRepositoryIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("mariaalpha")
          .withUsername("mariaalpha")
          .withPassword("mariaalpha");

  @DynamicPropertySource
  static void dsProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
  }

  @Autowired private OrderRepository orderRepository;
  @Autowired private FillRepository fillRepository;

  @BeforeEach
  void reset() {
    fillRepository.deleteAll();
    orderRepository.deleteAll();
  }

  @Test
  void existsByExchangeFillIdDetectsDuplicate() {
    var order = orderRepository.save(newOrder("AAPL", Side.BUY, "c1"));
    fillRepository.save(newFill(order, "EX-F-1", BigDecimal.valueOf(10)));

    assertThat(fillRepository.existsByExchangeFillId("EX-F-1")).isTrue();
    assertThat(fillRepository.existsByExchangeFillId("EX-F-2")).isFalse();
  }

  @Test
  void findByOrderOrderIdReturnsFillsInOrder() {
    var order = orderRepository.save(newOrder("AAPL", Side.BUY, "c1"));
    var first = newFill(order, "EX-F-1", BigDecimal.valueOf(10));
    first.setFilledAt(Instant.parse("2026-04-10T14:00:00Z"));
    var second = newFill(order, "EX-F-2", BigDecimal.valueOf(15));
    second.setFilledAt(Instant.parse("2026-04-10T14:05:00Z"));
    fillRepository.save(second);
    fillRepository.save(first);

    List<FillEntity> fills =
        fillRepository.findByOrder_OrderIdOrderByFilledAtAsc(order.getOrderId());
    assertThat(fills).hasSize(2);
    assertThat(fills.get(0).getExchangeFillId()).isEqualTo("EX-F-1");
    assertThat(fills.get(1).getExchangeFillId()).isEqualTo("EX-F-2");
  }

  @Test
  void findBySymbolReturnsFillsDescending() {
    var a = orderRepository.save(newOrder("AAPL", Side.BUY, "c1"));
    var b = orderRepository.save(newOrder("AAPL", Side.SELL, "c2"));
    var f1 = newFill(a, "EX-F-1", BigDecimal.valueOf(10));
    f1.setFilledAt(Instant.parse("2026-04-10T10:00:00Z"));
    var f2 = newFill(b, "EX-F-2", BigDecimal.valueOf(20));
    f2.setFilledAt(Instant.parse("2026-04-10T11:00:00Z"));
    fillRepository.save(f1);
    fillRepository.save(f2);

    List<FillEntity> result = fillRepository.findBySymbolOrderByFilledAtDesc("AAPL");
    assertThat(result).hasSize(2);
    assertThat(result.get(0).getExchangeFillId()).isEqualTo("EX-F-2");
  }

  @Test
  void commissionDefaultsToZeroWhenNotSet() {
    var order = orderRepository.save(newOrder("AAPL", Side.BUY, "c1"));
    var fill = newFill(order, "EX-F-1", BigDecimal.valueOf(5));
    fill.setCommission(BigDecimal.ZERO);
    var saved = fillRepository.save(fill);
    assertThat(saved.getCommission()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  private OrderEntity newOrder(String symbol, Side side, String clientId) {
    var order = new OrderEntity();
    order.setOrderId(UUID.randomUUID());
    order.setClientOrderId(clientId);
    order.setSymbol(symbol);
    order.setSide(side);
    order.setOrderType(OrderType.LIMIT);
    order.setQuantity(BigDecimal.valueOf(100));
    order.setStatus(OrderStatus.SUBMITTED);
    order.setFilledQuantity(BigDecimal.ZERO);
    return order;
  }

  private FillEntity newFill(OrderEntity order, String exchangeFillId, BigDecimal qty) {
    var fill = new FillEntity();
    fill.setFillId(UUID.randomUUID());
    fill.setOrder(order);
    fill.setSymbol(order.getSymbol());
    fill.setSide(order.getSide());
    fill.setFillPrice(BigDecimal.valueOf(150));
    fill.setFillQuantity(qty);
    fill.setCommission(BigDecimal.ZERO);
    fill.setVenue("SIMULATED");
    fill.setExchangeFillId(exchangeFillId);
    fill.setFilledAt(Instant.now());
    return fill;
  }
}
