package com.mariaalpha.posttrade.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.posttrade.model.MarketTickEvent;
import com.mariaalpha.posttrade.tca.MarketDataCache;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class MarketDataConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(MarketDataConsumer.class);

  private final ObjectMapper objectMapper;
  private final MarketDataCache cache;

  public MarketDataConsumer(ObjectMapper objectMapper, MarketDataCache cache) {
    this.objectMapper = objectMapper;
    this.cache = cache;
  }

  @KafkaListener(
      topics = "${post-trade.kafka.market-data-topic}",
      groupId = "post-trade-market-data")
  public void onTick(ConsumerRecord<String, String> record) {
    try {
      MarketTickEvent tick = objectMapper.readValue(record.value(), MarketTickEvent.class);
      cache.record(tick);
    } catch (Exception e) {
      LOG.warn(
          "Failed to deserialize market tick at partition {}, offset {}: {}",
          record.partition(),
          record.offset(),
          e.getMessage());
    }
  }
}
