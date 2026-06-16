package com.mariaalpha.ordermanager.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.ordermanager.model.OrderLifecycleEvent;
import com.mariaalpha.ordermanager.service.LifecycleEventHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderLifecycleConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(OrderLifecycleConsumer.class);

  private final ObjectMapper objectMapper;
  private final LifecycleEventHandler handler;

  public OrderLifecycleConsumer(ObjectMapper objectMapper, LifecycleEventHandler handler) {
    this.objectMapper = objectMapper;
    this.handler = handler;
  }

  @KafkaListener(topics = "${order-manager.kafka.orders-lifecycle-topic}")
  public void onLifecycleEvent(ConsumerRecord<String, String> record) {
    try {
      var event = objectMapper.readValue(record.value(), OrderLifecycleEvent.class);
      handler.handle(event);
    } catch (Exception e) {
      LOG.error(
          "Failed to process lifecycle event from partition {}, offset {}: {}",
          record.partition(),
          record.offset(),
          e.getMessage(),
          e);
    }
  }
}
