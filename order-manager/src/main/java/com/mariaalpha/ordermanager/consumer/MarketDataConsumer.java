package com.mariaalpha.ordermanager.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.ordermanager.model.MarketTickEvent;
import com.mariaalpha.ordermanager.service.PositionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MarketDataConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(MarketDataConsumer.class);
  private static final int SCALE = 8;

  private final ObjectMapper objectMapper;
  private final PositionService positionService;

  public MarketDataConsumer(ObjectMapper objectMapper, PositionService positionService) {
    this.objectMapper = objectMapper;
    this.positionService = positionService;
  }

  @KafkaListener(topics = "${order-manager.kafka.market-data-topic}")
  public void onTick(ConsumerRecord<String, String> record) {
    try {
      var tick = objectMapper.readValue(record.value(), MarketTickEvent.class);
      if (tick.stale()) {
        return;
      }
      var price = pickPrice(tick);
      if (price != null) {
        positionService.updateMarkPrice(tick.symbol(), price);
      }
    } catch (Exception e) {
      LOG.debug("Failed to parse market tick: {}", e.getMessage());
    }
  }

  @Scheduled(fixedDelayString = "${order-manager.mark-to-market.interval-ms:1000}")
  public void runMarkToMarket() {
    positionService.markToMarket();
  }

  // Prefer the last traded price as the most direct mark. Fall back to the mid-price
  // (bid + ask) / 2 when only quote data is available (e.g. pre-market or illiquid symbols).
  // Returns null if neither is present, in which case the mark price cache is left unchanged.
  private BigDecimal pickPrice(MarketTickEvent tick) {
    if (tick.price() != null) {
      return tick.price();
    }
    if (tick.bidPrice() != null && tick.askPrice() != null) {
      return tick.bidPrice()
          .add(tick.askPrice())
          .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
    }
    return null;
  }
}
