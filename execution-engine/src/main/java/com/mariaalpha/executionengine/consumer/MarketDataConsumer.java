package com.mariaalpha.executionengine.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.executionengine.model.MarketState;
import com.mariaalpha.executionengine.service.MarketStateTracker;
import java.math.BigDecimal;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class MarketDataConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(MarketDataConsumer.class);

  private final ObjectMapper objectMapper;
  private final MarketStateTracker marketStateTracker;

  public MarketDataConsumer(ObjectMapper objectMapper, MarketStateTracker marketStateTracker) {
    this.objectMapper = objectMapper;
    this.marketStateTracker = marketStateTracker;
  }

  @KafkaListener(
      topics = "${execution-engine.kafka.market-data-topic}",
      groupId = "execution-engine-market-data")
  public void onTick(ConsumerRecord<String, String> record) {
    try {
      var node = objectMapper.readTree(record.value());
      var state =
          new MarketState(
              node.get("symbol").asText(),
              new BigDecimal(node.get("bidPrice").asText()),
              new BigDecimal(node.get("askPrice").asText()),
              new BigDecimal(node.get("price").asText()),
              Instant.parse(node.get("timestamp").asText()));
      marketStateTracker.update(state);
    } catch (Exception e) {
      LOG.error("Failed to parse market data tick: {}", e.getMessage());
    }
  }
}
