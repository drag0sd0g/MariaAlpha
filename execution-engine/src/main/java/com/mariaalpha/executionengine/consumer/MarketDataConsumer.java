package com.mariaalpha.executionengine.consumer;

import com.fasterxml.jackson.databind.JsonNode;
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
      var symbol = node.get("symbol").asText();
      var previous = marketStateTracker.getMarketState(symbol);
      var state =
          new MarketState(
              symbol,
              merge(node, "bidPrice", previous == null ? null : previous.bidPrice()),
              merge(node, "askPrice", previous == null ? null : previous.askPrice()),
              merge(node, "price", previous == null ? null : previous.lastTradePrice()),
              Instant.parse(node.get("timestamp").asText()));
      marketStateTracker.update(state);
    } catch (Exception e) {
      LOG.error("Failed to parse market data tick: {}", e.getMessage());
    }
  }

  private static BigDecimal merge(JsonNode node, String field, BigDecimal previous) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return previous;
    }
    var parsed = new BigDecimal(value.asText());
    return parsed.signum() > 0 ? parsed : previous;
  }
}
