package com.mariaalpha.executionengine.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mariaalpha.executionengine.model.ExecutionInstruction;
import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlpacaOrderTypeMapperTest {

  private AlpacaOrderTypeMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new AlpacaOrderTypeMapper();
  }

  @Test
  void wireTypeForMarketLimitStopReturnsCanonical() {
    assertThat(mapper.wireType(OrderType.MARKET)).isEqualTo("market");
    assertThat(mapper.wireType(OrderType.LIMIT)).isEqualTo("limit");
    assertThat(mapper.wireType(OrderType.STOP)).isEqualTo("stop");
  }

  @Test
  void wireTypeFlattensIocFokGtcToLimit() {
    assertThat(mapper.wireType(OrderType.IOC)).isEqualTo("limit");
    assertThat(mapper.wireType(OrderType.FOK)).isEqualTo("limit");
    assertThat(mapper.wireType(OrderType.GTC)).isEqualTo("limit");
  }

  @Test
  void wireTypeRejectsIcebergDirectSubmission() {
    assertThatThrownBy(() -> mapper.wireType(OrderType.ICEBERG))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ICEBERG");
  }

  @Test
  void wireTifReturnsDayForDay() {
    var instruction = instruction(OrderType.LIMIT, TimeInForce.DAY);
    assertThat(mapper.wireTif(instruction)).isEqualTo("day");
  }

  @Test
  void wireTifReturnsIocForIoc() {
    var instruction = instruction(OrderType.IOC, TimeInForce.IOC);
    assertThat(mapper.wireTif(instruction)).isEqualTo("ioc");
  }

  @Test
  void wireTifReturnsFokForFok() {
    var instruction = instruction(OrderType.FOK, TimeInForce.FOK);
    assertThat(mapper.wireTif(instruction)).isEqualTo("fok");
  }

  @Test
  void wireTifReturnsGtcForGtc() {
    var instruction = instruction(OrderType.GTC, TimeInForce.GTC);
    assertThat(mapper.wireTif(instruction)).isEqualTo("gtc");
  }

  private ExecutionInstruction instruction(OrderType type, TimeInForce tif) {
    var order =
        new Order(
            new OrderSignal(
                "AAPL",
                Side.BUY,
                100,
                type,
                new BigDecimal("150.00"),
                null,
                "MANUAL",
                Instant.now()));
    return new ExecutionInstruction(order, tif, new BigDecimal("150.00"));
  }
}
