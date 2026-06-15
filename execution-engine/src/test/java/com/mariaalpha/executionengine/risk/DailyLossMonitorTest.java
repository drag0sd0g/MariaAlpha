package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mariaalpha.executionengine.config.RiskLimitsConfig;
import com.mariaalpha.executionengine.model.Fill;
import com.mariaalpha.executionengine.model.RiskAlert;
import com.mariaalpha.executionengine.model.Side;
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
    var config =
        new RiskLimitsConfig(
            100_000, 500_000, 2_000_000, 50, 25_000, java.util.Map.of(), 0L, 0L, 0.0);
    monitor = new DailyLossMonitor(config, alertPublisher);
  }

  private static Fill fill(String symbol, Side side, String price, int qty) {
    return new Fill(
        "fill-" + System.nanoTime(),
        "order-1",
        symbol,
        side,
        new BigDecimal(price),
        qty,
        null,
        "SIMULATED",
        Instant.now());
  }

  @Test
  void startsUnhalted() {
    assertThat(monitor.isTradingHalted()).isFalse();
  }

  @Test
  void buyFillAloneRealizesNothing() {
    monitor.onFill(fill("AAPL", Side.BUY, "170.00", 1000));
    assertThat(monitor.getDailyPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(monitor.isTradingHalted()).isFalse();
  }

  @Test
  void haltsOnLossBreachCritical() {
    monitor.onFill(fill("AAPL", Side.BUY, "170.00", 1000));
    monitor.onFill(fill("AAPL", Side.SELL, "140.00", 1000));

    assertThat(monitor.getDailyPnl()).isEqualByComparingTo(new BigDecimal("-30000"));
    assertThat(monitor.isTradingHalted()).isTrue();
  }

  @Test
  void doesNotHaltWhenWithinLimit() {
    monitor.onFill(fill("AAPL", Side.BUY, "150.00", 100));
    monitor.onFill(fill("AAPL", Side.SELL, "149.00", 100));

    assertThat(monitor.getDailyPnl()).isEqualByComparingTo(new BigDecimal("-100"));
    assertThat(monitor.isTradingHalted()).isFalse();
  }

  @Test
  void shortCoverRealizesPnl() {
    monitor.onFill(fill("TSLA", Side.SELL, "150.00", 500));
    monitor.onFill(fill("TSLA", Side.BUY, "160.00", 500));

    assertThat(monitor.getDailyPnl()).isEqualByComparingTo(new BigDecimal("-5000"));
  }

  @Test
  void flipRealizesOnlyClosedPortion() {
    monitor.onFill(fill("MSFT", Side.BUY, "100.00", 100));
    monitor.onFill(fill("MSFT", Side.SELL, "90.00", 300));
    assertThat(monitor.getDailyPnl()).isEqualByComparingTo(new BigDecimal("-1000"));

    monitor.onFill(fill("MSFT", Side.BUY, "80.00", 200));
    assertThat(monitor.getDailyPnl()).isEqualByComparingTo(new BigDecimal("1000"));
  }

  @Test
  void resumeUnhaltsTrading() {
    monitor.onFill(fill("AAPL", Side.BUY, "170.00", 1000));
    monitor.onFill(fill("AAPL", Side.SELL, "140.00", 1000));
    assertThat(monitor.isTradingHalted()).isTrue();

    monitor.resume();
    assertThat(monitor.isTradingHalted()).isFalse();
  }

  @Test
  void publishesCriticalAlertOnHalt() {
    monitor.onFill(fill("AAPL", Side.BUY, "170.00", 1000));
    monitor.onFill(fill("AAPL", Side.SELL, "140.00", 1000));

    var captor = ArgumentCaptor.forClass(RiskAlert.class);
    verify(alertPublisher).publish(captor.capture());
    assertThat(captor.getValue().severity()).isEqualTo("CRITICAL");
    assertThat(captor.getValue().alertType()).isEqualTo("DAILY_LOSS_LIMIT_BREACH");
  }

  @Test
  void resetClearsPnl() {
    monitor.onFill(fill("AAPL", Side.BUY, "150.00", 100));
    monitor.onFill(fill("AAPL", Side.SELL, "149.00", 100));

    monitor.resetDailyLimits();
    assertThat(monitor.getDailyPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(monitor.isTradingHalted()).isFalse();
  }
}
