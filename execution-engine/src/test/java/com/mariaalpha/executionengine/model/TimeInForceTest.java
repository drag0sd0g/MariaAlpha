package com.mariaalpha.executionengine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TimeInForceTest {

  @Test
  void wireValueMatchesAlpacaContract() {
    assertThat(TimeInForce.DAY.wireValue()).isEqualTo("day");
    assertThat(TimeInForce.IOC.wireValue()).isEqualTo("ioc");
    assertThat(TimeInForce.FOK.wireValue()).isEqualTo("fok");
    assertThat(TimeInForce.GTC.wireValue()).isEqualTo("gtc");
  }

  @Test
  void fromWireValueRoundTripsCaseInsensitive() {
    assertThat(TimeInForce.fromWireValue("day")).isEqualTo(TimeInForce.DAY);
    assertThat(TimeInForce.fromWireValue("IOC")).isEqualTo(TimeInForce.IOC);
    assertThat(TimeInForce.fromWireValue("Fok")).isEqualTo(TimeInForce.FOK);
    assertThat(TimeInForce.fromWireValue("gtc")).isEqualTo(TimeInForce.GTC);
  }

  @Test
  void fromWireValueRejectsUnknown() {
    assertThatThrownBy(() -> TimeInForce.fromWireValue("opg"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown TimeInForce wire value");
  }
}
