package com.mariaalpha.executionengine.controller.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.executionengine.controller.dto.SubmitOrderRequest;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.Side;
import com.mariaalpha.executionengine.model.TimeInForce;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SubmitOrderRequestConstraintsTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  @Test
  void validLimitOrderPasses() {
    var request =
        new SubmitOrderRequest(
            "AAPL",
            Side.BUY,
            OrderType.LIMIT,
            100,
            new BigDecimal("150.00"),
            null,
            null,
            null,
            "client-1");
    assertThat(violationMessages(request)).isEmpty();
  }

  @Test
  void validIcebergOrderPasses() {
    var request =
        new SubmitOrderRequest(
            "AAPL",
            Side.BUY,
            OrderType.ICEBERG,
            10_000,
            new BigDecimal("150.00"),
            null,
            1_000,
            null,
            "client-1");
    assertThat(violationMessages(request)).isEmpty();
  }

  @Test
  void icebergMissingDisplayQuantityIsRejected() {
    var request =
        new SubmitOrderRequest(
            "AAPL",
            Side.BUY,
            OrderType.ICEBERG,
            10_000,
            new BigDecimal("150.00"),
            null,
            null,
            null,
            null);
    assertThat(violationMessages(request))
        .anyMatch(msg -> msg.contains("displayQuantity is required for ICEBERG"));
  }

  @Test
  void nonIcebergWithDisplayQuantityIsRejected() {
    var request =
        new SubmitOrderRequest(
            "AAPL", Side.BUY, OrderType.LIMIT, 100, new BigDecimal("150.00"), null, 50, null, null);
    assertThat(violationMessages(request))
        .anyMatch(msg -> msg.contains("displayQuantity is only valid for ICEBERG"));
  }

  @Test
  void icebergDisplayQuantityNotLessThanQuantityIsRejected() {
    var request =
        new SubmitOrderRequest(
            "AAPL",
            Side.BUY,
            OrderType.ICEBERG,
            1_000,
            new BigDecimal("150.00"),
            null,
            1_000,
            null,
            null);
    assertThat(violationMessages(request))
        .anyMatch(msg -> msg.contains("displayQuantity must be strictly less than quantity"));
  }

  @Test
  void marketWithNonDayTifIsRejected() {
    var request =
        new SubmitOrderRequest(
            "AAPL", Side.BUY, OrderType.MARKET, 100, null, null, null, TimeInForce.GTC, null);
    assertThat(violationMessages(request))
        .anyMatch(msg -> msg.contains("MARKET and STOP orders only accept tif=DAY"));
  }

  @Test
  void iocWithFokTifIsRejected() {
    var request =
        new SubmitOrderRequest(
            "AAPL",
            Side.BUY,
            OrderType.IOC,
            100,
            new BigDecimal("150.00"),
            null,
            null,
            TimeInForce.FOK,
            null);
    assertThat(violationMessages(request))
        .anyMatch(msg -> msg.contains("IOC orders only accept tif=IOC"));
  }

  @Test
  void fokWithIocTifIsRejected() {
    var request =
        new SubmitOrderRequest(
            "AAPL",
            Side.BUY,
            OrderType.FOK,
            100,
            new BigDecimal("150.00"),
            null,
            null,
            TimeInForce.IOC,
            null);
    assertThat(violationMessages(request))
        .anyMatch(msg -> msg.contains("FOK orders only accept tif=FOK"));
  }

  @Test
  void limitMissingLimitPriceIsRejected() {
    var request =
        new SubmitOrderRequest(
            "AAPL", Side.BUY, OrderType.LIMIT, 100, null, null, null, null, null);
    assertThat(violationMessages(request))
        .anyMatch(msg -> msg.contains("LIMIT orders require limitPrice"));
  }

  @Test
  void stopMissingStopPriceIsRejected() {
    var request =
        new SubmitOrderRequest("AAPL", Side.BUY, OrderType.STOP, 100, null, null, null, null, null);
    assertThat(violationMessages(request))
        .anyMatch(msg -> msg.contains("STOP orders require stopPrice"));
  }

  private Set<String> violationMessages(SubmitOrderRequest request) {
    return validator.validate(request).stream()
        .map(ConstraintViolation::getMessage)
        .collect(java.util.stream.Collectors.toSet());
  }
}
