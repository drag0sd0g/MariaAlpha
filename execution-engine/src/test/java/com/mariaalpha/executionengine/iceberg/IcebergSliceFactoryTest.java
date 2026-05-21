package com.mariaalpha.executionengine.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IcebergSliceFactoryTest {

  private IcebergSliceFactory factory;

  @BeforeEach
  void setUp() {
    factory = new IcebergSliceFactory();
  }

  @Test
  void createChild_inheritsParentSymbolSideAndLimit() {
    var parent = createParent(6000, 1000);
    var child = factory.createChild(parent, 1000, 0);

    assertThat(child.getSymbol()).isEqualTo(parent.getSymbol());
    assertThat(child.getSide()).isEqualTo(parent.getSide());
    assertThat(child.getLimitPrice()).isEqualByComparingTo(parent.getLimitPrice());
  }

  @Test
  void createChild_setsQuantityToSliceQty() {
    var parent = createParent(6000, 1000);
    var child = factory.createChild(parent, 1000, 2);
    assertThat(child.getQuantity()).isEqualTo(1000);
  }

  @Test
  void createChild_setsOrderTypeToLimit() {
    var parent = createParent(6000, 1000);
    var child = factory.createChild(parent, 1000, 0);
    assertThat(child.getOrderType()).isEqualTo(OrderType.LIMIT);
  }

  @Test
  void createChild_setsParentOrderId() {
    var parent = createParent(6000, 1000);
    var child = factory.createChild(parent, 1000, 0);
    assertThat(child.getParentOrderId()).isEqualTo(parent.getOrderId());
  }

  @Test
  void createChild_setsStrategyToIcebergChild() {
    var parent = createParent(6000, 1000);
    var child = factory.createChild(parent, 1000, 0);
    assertThat(child.getStrategyName()).isEqualTo("ICEBERG-CHILD");
  }

  private Order createParent(int qty, int displayQty) {
    var signal =
        new OrderSignal(
            "AAPL",
            Side.BUY,
            qty,
            OrderType.ICEBERG,
            new BigDecimal("150.00"),
            null,
            "MANUAL",
            Instant.now(),
            displayQty,
            TimeInForce.DAY,
            null);
    return new Order(signal);
  }
}
