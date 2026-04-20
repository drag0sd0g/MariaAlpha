package com.mariaalpha.ordermanager.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OrderRepositoryIntegrationTest {

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

  @BeforeEach
  void reset() {
    orderRepository.deleteAll();
  }

  @Test
  void savesAndRetrievesByClientOrderId() {
    var order = newOrder("AAPL", Side.BUY, OrderStatus.NEW, "alpha-1");
    orderRepository.save(order);

    assertThat(orderRepository.findByClientOrderId("alpha-1")).isPresent();
    assertThat(orderRepository.existsByClientOrderId("alpha-1")).isTrue();
    assertThat(orderRepository.existsByClientOrderId("missing")).isFalse();
  }

  @Test
  void enforcesClientOrderIdUniqueness() {
    orderRepository.save(newOrder("AAPL", Side.BUY, OrderStatus.NEW, "dup"));
    var dup = newOrder("MSFT", Side.SELL, OrderStatus.NEW, "dup");
    try {
      orderRepository.saveAndFlush(dup);
      assertThat(false).as("expected DataIntegrityViolationException").isTrue();
    } catch (Exception e) {
      assertThat(e).isNotNull();
    }
  }

  @Test
  void searchFiltersBySymbol() {
    orderRepository.save(newOrder("AAPL", Side.BUY, OrderStatus.NEW, "c1"));
    orderRepository.save(newOrder("MSFT", Side.BUY, OrderStatus.NEW, "c2"));
    orderRepository.save(newOrder("AAPL", Side.SELL, OrderStatus.FILLED, "c3"));

    List<OrderEntity> results =
        orderRepository.search("AAPL", null, null, null, null, PageRequest.of(0, 10));
    assertThat(results).hasSize(2).allMatch(o -> o.getSymbol().equals("AAPL"));
  }

  @Test
  void searchFiltersByStatus() {
    orderRepository.save(newOrder("AAPL", Side.BUY, OrderStatus.NEW, "c1"));
    orderRepository.save(newOrder("AAPL", Side.BUY, OrderStatus.FILLED, "c2"));

    List<OrderEntity> results =
        orderRepository.search(null, OrderStatus.FILLED, null, null, null, PageRequest.of(0, 10));
    assertThat(results).hasSize(1).allMatch(o -> o.getStatus() == OrderStatus.FILLED);
  }

  @Test
  void searchFiltersByStrategy() {
    var vwap = newOrder("AAPL", Side.BUY, OrderStatus.NEW, "c1");
    vwap.setStrategy("VWAP");
    var momentum = newOrder("MSFT", Side.BUY, OrderStatus.NEW, "c2");
    momentum.setStrategy("MOMENTUM");
    orderRepository.save(vwap);
    orderRepository.save(momentum);

    List<OrderEntity> results =
        orderRepository.search(null, null, "VWAP", null, null, PageRequest.of(0, 10));
    assertThat(results).hasSize(1).allMatch(o -> "VWAP".equals(o.getStrategy()));
  }

  @Test
  void searchFiltersByTimeWindow() {
    var old = newOrder("AAPL", Side.BUY, OrderStatus.FILLED, "c1");
    old.setCreatedAt(Instant.parse("2026-03-01T00:00:00Z"));
    old.setUpdatedAt(Instant.parse("2026-03-01T00:00:00Z"));
    var recent = newOrder("AAPL", Side.BUY, OrderStatus.FILLED, "c2");
    recent.setCreatedAt(Instant.parse("2026-04-15T00:00:00Z"));
    recent.setUpdatedAt(Instant.parse("2026-04-15T00:00:00Z"));
    orderRepository.save(old);
    orderRepository.save(recent);

    List<OrderEntity> results =
        orderRepository.search(
            null,
            null,
            null,
            Instant.parse("2026-04-01T00:00:00Z"),
            Instant.parse("2026-05-01T00:00:00Z"),
            PageRequest.of(0, 10));
    assertThat(results).hasSize(1).allMatch(o -> o.getClientOrderId().equals("c2"));
  }

  @Test
  void searchOrdersByCreatedAtDesc() {
    var first = newOrder("AAPL", Side.BUY, OrderStatus.NEW, "c1");
    first.setCreatedAt(Instant.parse("2026-04-01T00:00:00Z"));
    first.setUpdatedAt(Instant.parse("2026-04-01T00:00:00Z"));
    var second = newOrder("AAPL", Side.BUY, OrderStatus.NEW, "c2");
    second.setCreatedAt(Instant.parse("2026-04-10T00:00:00Z"));
    second.setUpdatedAt(Instant.parse("2026-04-10T00:00:00Z"));
    orderRepository.save(first);
    orderRepository.save(second);

    List<OrderEntity> results =
        orderRepository.search(null, null, null, null, null, PageRequest.of(0, 10));
    assertThat(results).hasSize(2);
    assertThat(results.get(0).getClientOrderId()).isEqualTo("c2");
    assertThat(results.get(1).getClientOrderId()).isEqualTo("c1");
  }

  @Test
  void searchRespectsLimit() {
    for (int i = 0; i < 5; i++) {
      orderRepository.save(newOrder("AAPL", Side.BUY, OrderStatus.NEW, "c" + i));
    }

    List<OrderEntity> results =
        orderRepository.search(null, null, null, null, null, PageRequest.of(0, 3));
    assertThat(results).hasSize(3);
  }

  @Test
  void findByExchangeOrderIdReturnsMatch() {
    var order = newOrder("AAPL", Side.BUY, OrderStatus.SUBMITTED, "c1");
    order.setExchangeOrderId("EX-123");
    orderRepository.save(order);

    assertThat(orderRepository.findByExchangeOrderId("EX-123")).isPresent();
    assertThat(orderRepository.findByExchangeOrderId("EX-999")).isEmpty();
  }

  private OrderEntity newOrder(String symbol, Side side, OrderStatus status, String clientId) {
    var order = new OrderEntity();
    order.setOrderId(UUID.randomUUID());
    order.setClientOrderId(clientId);
    order.setSymbol(symbol);
    order.setSide(side);
    order.setOrderType(OrderType.LIMIT);
    order.setQuantity(BigDecimal.valueOf(100));
    order.setLimitPrice(BigDecimal.valueOf(150));
    order.setStatus(status);
    order.setFilledQuantity(BigDecimal.ZERO);
    return order;
  }
}
