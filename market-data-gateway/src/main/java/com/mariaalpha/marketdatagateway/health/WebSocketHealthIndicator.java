package com.mariaalpha.marketdatagateway.health;

import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("websocket")
public class WebSocketHealthIndicator implements HealthIndicator {

  private final MarketDataAdapter adapter;

  public WebSocketHealthIndicator(MarketDataAdapter adapter) {
    this.adapter = adapter;
  }

  @Override
  public Health health() {
    if (adapter.isConnected()) {
      return Health.up().withDetail("connection", "active").build();
    }
    return Health.down().withDetail("connection", "disconnected").build();
  }
}
