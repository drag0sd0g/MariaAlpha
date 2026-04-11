package com.mariaalpha.marketdatagateway.websocket;

import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("alpaca")
public class WebSocketRunner implements ApplicationRunner {
  private static final Logger LOG = LoggerFactory.getLogger(WebSocketRunner.class);

  private final MarketDataAdapter adapter;
  private final List<String> symbols;

  public WebSocketRunner(
      MarketDataAdapter adapter, @Value("${market-data.symbols}") List<String> symbols) {
    this.adapter = adapter;
    this.symbols = symbols;
  }

  @Override
  public void run(ApplicationArguments args) {
    LOG.info("Starting WebSocket connection for symbols: {}", symbols);
    adapter.connect(symbols);
  }
}
