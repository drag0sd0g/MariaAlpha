package com.mariaalpha.ordermanager.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.ordermanager.controller.dto.PositionSnapshot;
import com.mariaalpha.ordermanager.model.OrderLifecycleEvent;
import com.mariaalpha.ordermanager.publisher.PositionUpdatePublisher;
import com.mariaalpha.ordermanager.service.OrderPersistenceService;
import com.mariaalpha.ordermanager.service.PositionService;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderLifecycleConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(OrderLifecycleConsumer.class);

  private final ObjectMapper objectMapper;
  private final OrderPersistenceService persistenceService;
  private final PositionService positionService;
  private final PositionUpdatePublisher publisher;

  public OrderLifecycleConsumer(
      ObjectMapper objectMapper,
      OrderPersistenceService persistenceService,
      PositionService positionService,
      PositionUpdatePublisher publisher) {
    this.objectMapper = objectMapper;
    this.persistenceService = persistenceService;
    this.positionService = positionService;
    this.publisher = publisher;
  }

  @KafkaListener(topics = "${order-manager.kafka.orders-lifecycle-topic}")
  public void onLifecycleEvent(ConsumerRecord<String, String> record) {
    try {
      var event = objectMapper.readValue(record.value(), OrderLifecycleEvent.class);
      handle(event);
    } catch (Exception e) {
      LOG.error(
          "Failed to process lifecycle event from partition {}, offset {}: {}",
          record.partition(),
          record.offset(),
          e.getMessage(),
          e);
    }
  }

  @Transactional
  public void handle(OrderLifecycleEvent event) {
    if (event == null || event.order() == null) {
      LOG.warn("dropping lifecycle event with null payload");
      return;
    }
    var orderEntity = persistenceService.upsertOrder(event);
    var persisted = persistenceService.persistFillIfAbsent(orderEntity, event.fill());
    persisted.ifPresent(
        fill -> {
          var position = positionService.applyFill(fill);
          var positionSnapshot =
              new PositionSnapshot(
                  position.getSymbol(),
                  position.getNetQuantity(),
                  position.getAvgEntryPrice(),
                  position.getRealizedPnl(),
                  position.getUnrealizedPnl(),
                  position.getLastMarkPrice(),
                  Instant.now());
          publisher.publish(positionSnapshot);
        });
  }
}
