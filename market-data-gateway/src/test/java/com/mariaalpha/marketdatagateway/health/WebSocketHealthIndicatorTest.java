package com.mariaalpha.marketdatagateway.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class WebSocketHealthIndicatorTest {

  private final MarketDataAdapter adapter = mock(MarketDataAdapter.class);
  private final WebSocketHealthIndicator indicator = new WebSocketHealthIndicator(adapter);

  @Test
  void reportsUpWhenConnected() {
    when(adapter.isConnected()).thenReturn(true);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("connection", "active");
  }

  @Test
  void reportsDownWhenDisconnected() {
    when(adapter.isConnected()).thenReturn(false);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("connection", "disconnected");
  }
}
