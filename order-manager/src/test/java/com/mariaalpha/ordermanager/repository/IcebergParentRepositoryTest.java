package com.mariaalpha.ordermanager.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class IcebergParentRepositoryTest {

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
  void icebergParentTypeRoundTrips() {
    var parent = newOrder(OrderType.ICEBERG, "ice-1");
    parent.setDisplayQuantity(BigDecimal.valueOf(100));
    orderRepository.save(parent);

    var loaded = orderRepository.findByClientOrderId("ice-1").orElseThrow();
    assertThat(loaded.getOrderType()).isEqualTo(OrderType.ICEBERG);
    assertThat(loaded.getDisplayQuantity()).isEqualByComparingTo("100");
  }

  @Test
  void parentOrderIdRoundTrips() {
    var parent = newOrder(OrderType.ICEBERG, "ice-parent");
    parent.setDisplayQuantity(BigDecimal.valueOf(100));
    var savedParent = orderRepository.save(parent);

    var child = newOrder(OrderType.LIMIT, "ice-child-1");
    child.setParentOrderId(savedParent.getOrderId());
    orderRepository.save(child);

    var loaded = orderRepository.findByClientOrderId("ice-child-1").orElseThrow();
    assertThat(loaded.getParentOrderId()).isEqualTo(savedParent.getOrderId());
  }

  @Test
  void findByParentOrderId_returnsAllChildren() {
    var parent = newOrder(OrderType.ICEBERG, "ice-parent");
    parent.setDisplayQuantity(BigDecimal.valueOf(100));
    var savedParent = orderRepository.save(parent);

    var child1 = newOrder(OrderType.LIMIT, "ice-c-1");
    child1.setParentOrderId(savedParent.getOrderId());
    var child2 = newOrder(OrderType.LIMIT, "ice-c-2");
    child2.setParentOrderId(savedParent.getOrderId());
    orderRepository.save(child1);
    orderRepository.save(child2);

    var children = orderRepository.findByParentOrderId(savedParent.getOrderId());
    assertThat(children).hasSize(2);
    assertThat(children)
        .extracting(OrderEntity::getClientOrderId)
        .containsExactlyInAnyOrder("ice-c-1", "ice-c-2");
  }

  @Test
  void displayQuantityCheckConstraintRejectsEqualToQuantity() {
    var bad = newOrder(OrderType.ICEBERG, "ice-bad");
    bad.setQuantity(BigDecimal.valueOf(100));
    bad.setDisplayQuantity(BigDecimal.valueOf(100));

    assertThatThrownBy(() -> orderRepository.saveAndFlush(bad))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void displayQuantityCheckConstraintRejectsZero() {
    var bad = newOrder(OrderType.ICEBERG, "ice-bad-zero");
    bad.setQuantity(BigDecimal.valueOf(100));
    bad.setDisplayQuantity(BigDecimal.valueOf(0));

    assertThatThrownBy(() -> orderRepository.saveAndFlush(bad))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private OrderEntity newOrder(OrderType type, String clientId) {
    var order = new OrderEntity();
    order.setOrderId(UUID.randomUUID());
    order.setClientOrderId(clientId);
    order.setSymbol("AAPL");
    order.setSide(Side.BUY);
    order.setOrderType(type);
    order.setQuantity(BigDecimal.valueOf(1000));
    order.setLimitPrice(BigDecimal.valueOf(150));
    order.setStatus(OrderStatus.NEW);
    order.setFilledQuantity(BigDecimal.ZERO);
    return order;
  }
}
