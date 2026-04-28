package com.mariaalpha.posttrade.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.posttrade.model.OrderLifecycleEvent;
import com.mariaalpha.posttrade.model.OrderStatus;
import com.mariaalpha.posttrade.service.ArrivalSnapshotService;
import com.mariaalpha.posttrade.service.TcaService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderLifecycleConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(OrderLifecycleConsumer.class);

  private final ObjectMapper objectMapper;
  private final ArrivalSnapshotService arrivalSnapshotService;
  private final TcaService tcaService;

  public OrderLifecycleConsumer(
      ObjectMapper objectMapper,
      ArrivalSnapshotService arrivalSnapshotService,
      TcaService tcaService) {
    this.objectMapper = objectMapper;
    this.arrivalSnapshotService = arrivalSnapshotService;
    this.tcaService = tcaService;
  }

  @KafkaListener(topics = "${post-trade.kafka.orders-lifecycle-topic}")
  public void onLifecycleEvent(ConsumerRecord<String, String> record) {
    OrderLifecycleEvent event;
    try {
      event = objectMapper.readValue(record.value(), OrderLifecycleEvent.class);
    } catch (Exception e) {
      LOG.error(
          "Failed to deserialize lifecycle event at partition {}, offset {}: {}",
          record.partition(),
          record.offset(),
          e.getMessage());
      return;
    }
    handle(event);
  }

  void handle(OrderLifecycleEvent event) {
    if (event == null || event.orderId() == null) {
      return;
    }
    arrivalSnapshotService.captureIfAbsent(event);
    if (event.status() == OrderStatus.FILLED) {
      try {
        UUID orderId = UUID.fromString(event.orderId());
        tcaService.computeForCompletedOrder(orderId);
      } catch (IllegalArgumentException e) {
        LOG.warn("Cannot parse orderId '{}' as UUID", event.orderId());
      } catch (Exception e) {
        LOG.error("TCA computation failed for order {}: {}", event.orderId(), e.getMessage(), e);
      }
    }
  }
}
