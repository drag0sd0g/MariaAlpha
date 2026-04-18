package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Fill;
import com.mariaalpha.executionengine.model.RiskAlert;
import com.mariaalpha.executionengine.publisher.RiskAlertPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DailyLossMonitorTest {

  private RiskAlertPublisher alertPublisher;
  private DailyLossMonitor monitor;

  @BeforeEach
  void setUp() {
    alertPublisher = mock(RiskAlertPublisher.class);
    var config = new RiskLimitsConfig(100_000, 500_000, 2_000_000, 50, 25_000);
    monitor = new DailyLossMonitor(config, alertPublisher);
  }

  @Test
  void startsUnhalted() {
    assertThat(monitor.isTradingHalted()).isFalse();
  }

  @Test
  void haltsOnLossBreachCritical() {
    // Simulate losing fill: sold at $140 but entered at $170 → loss = -$30 * 1000 = -$30K
    var fill =
        new Fill("fill-1", "order-1", new BigDecimal("140.00"), 1000, "SIMULATED", Instant.now());
    monitor.onFill(fill, new BigDecimal("170.00"), "AAPL");

    assertThat(monitor.isTradingHalted()).isTrue();
  }

  @Test
  void doesNotHaltWhenWithinLimit() {
    // Small loss: sold at $149 entered at $150 → loss = -$1 * 100 = -$100
    var fill =
        new Fill("fill-1", "order-1", new BigDecimal("149.00"), 100, "SIMULATED", Instant.now());
    monitor.onFill(fill, new BigDecimal("150.00"), "AAPL");

    assertThat(monitor.isTradingHalted()).isFalse();
  }

  @Test
  void resumeUnhaltsTrading() {
    // Trigger halt
    var fill =
        new Fill("fill-1", "order-1", new BigDecimal("140.00"), 1000, "SIMULATED", Instant.now());
    monitor.onFill(fill, new BigDecimal("170.00"), "AAPL");
    assertThat(monitor.isTradingHalted()).isTrue();

    monitor.resume();
    assertThat(monitor.isTradingHalted()).isFalse();
  }

  @Test
  void publishesCriticalAlertOnHalt() {
    var fill =
        new Fill("fill-1", "order-1", new BigDecimal("140.00"), 1000, "SIMULATED", Instant.now());
    monitor.onFill(fill, new BigDecimal("170.00"), "AAPL");

    var captor = ArgumentCaptor.forClass(RiskAlert.class);
    verify(alertPublisher).publish(captor.capture());
    assertThat(captor.getValue().severity()).isEqualTo("CRITICAL");
    assertThat(captor.getValue().alertType()).isEqualTo("DAILY_LOSS_LIMIT_BREACH");
  }

  @Test
  void resetClearsPnl() {
    // Accumulate some loss
    var fill =
        new Fill("fill-1", "order-1", new BigDecimal("149.00"), 100, "SIMULATED", Instant.now());
    monitor.onFill(fill, new BigDecimal("150.00"), "AAPL");

    monitor.resetDailyLimits();
    assertThat(monitor.getDailyPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(monitor.isTradingHalted()).isFalse();
  }
}
