package com.mariaalpha.ordermanager.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.ordermanager.entity.OrderEntity;
import com.mariaalpha.ordermanager.model.OrderStatus;
import com.mariaalpha.ordermanager.model.OrderType;
import com.mariaalpha.ordermanager.model.Side;
import java.math.BigDecimal;
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

/**
 * Verifies that the IOC and FOK order types round-trip correctly through the JPA layer. The {@code
 * order_type} column is a {@code varchar(16)} mapped via {@code @Enumerated(EnumType.STRING)} so
 * each new enum value must be writable and readable without altering the column definition.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class IocFokOrderTypePersistenceTest {

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
  void iocOrderTypeRoundTrips() {
    var saved = orderRepository.save(newOrder(OrderType.IOC, "ioc-1"));

    var loaded = orderRepository.findByClientOrderId("ioc-1").orElseThrow();
    assertThat(loaded.getOrderType()).isEqualTo(OrderType.IOC);
    assertThat(loaded.getOrderId()).isEqualTo(saved.getOrderId());
  }

  @Test
  void fokOrderTypeRoundTrips() {
    orderRepository.save(newOrder(OrderType.FOK, "fok-1"));

    var loaded = orderRepository.findByClientOrderId("fok-1").orElseThrow();
    assertThat(loaded.getOrderType()).isEqualTo(OrderType.FOK);
  }

  @Test
  void searchByIocAndFokDoesNotCollideWithLimit() {
    orderRepository.save(newOrder(OrderType.LIMIT, "limit-1"));
    orderRepository.save(newOrder(OrderType.IOC, "ioc-1"));
    orderRepository.save(newOrder(OrderType.FOK, "fok-1"));

    assertThat(orderRepository.findByClientOrderId("limit-1").orElseThrow().getOrderType())
        .isEqualTo(OrderType.LIMIT);
    assertThat(orderRepository.findByClientOrderId("ioc-1").orElseThrow().getOrderType())
        .isEqualTo(OrderType.IOC);
    assertThat(orderRepository.findByClientOrderId("fok-1").orElseThrow().getOrderType())
        .isEqualTo(OrderType.FOK);
  }

  private OrderEntity newOrder(OrderType type, String clientId) {
    var order = new OrderEntity();
    order.setOrderId(UUID.randomUUID());
    order.setClientOrderId(clientId);
    order.setSymbol("AAPL");
    order.setSide(Side.BUY);
    order.setOrderType(type);
    order.setQuantity(BigDecimal.valueOf(100));
    order.setLimitPrice(BigDecimal.valueOf(150));
    order.setStatus(OrderStatus.NEW);
    order.setFilledQuantity(BigDecimal.ZERO);
    return order;
  }
}
