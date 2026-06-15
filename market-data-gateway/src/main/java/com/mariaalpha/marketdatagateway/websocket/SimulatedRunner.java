package com.mariaalpha.marketdatagateway.websocket;

import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import com.mariaalpha.marketdatagateway.book.OrderBookManager;
import com.mariaalpha.marketdatagateway.health.TickReadinessIndicator;
import com.mariaalpha.marketdatagateway.publisher.TickKafkaPublisher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("simulated")
public class SimulatedRunner implements ApplicationRunner {
  private static final Logger LOG = LoggerFactory.getLogger(SimulatedRunner.class);

  private final MarketDataAdapter adapter;
  private final TickKafkaPublisher publisher;
  private final OrderBookManager orderBookManager;
  private final TickReadinessIndicator tickReadinessIndicator;
  private final List<String> symbols;

  public SimulatedRunner(
      MarketDataAdapter adapter,
      TickKafkaPublisher publisher,
      OrderBookManager orderBookManager,
      TickReadinessIndicator tickReadinessIndicator,
      @Value("${market-data.symbols}") List<String> symbols) {
    this.adapter = adapter;
    this.publisher = publisher;
    this.orderBookManager = orderBookManager;
    this.tickReadinessIndicator = tickReadinessIndicator;
    this.symbols = List.copyOf(symbols);
  }

  @Override
  public void run(ApplicationArguments args) {
    LOG.info("Starting simulated market data for symbols: {}", symbols);
    adapter.connect(symbols);
    publisher.startStreaming();
    orderBookManager.restart();
    tickReadinessIndicator.restart();
  }
}
