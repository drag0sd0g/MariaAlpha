package com.mariaalpha.posttrade.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.posttrade.config.KafkaConfig;
import com.mariaalpha.posttrade.entity.TcaResultEntity;
import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class TcaResultPublisherTest {

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final KafkaConfig kafkaConfig =
      new KafkaConfig("orders.lifecycle", "market-data.ticks", "analytics.tca");

  @Test
  @SuppressWarnings("unchecked")
  void publishesJsonToConfiguredTopic() {
    KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    TcaResultPublisher publisher = new TcaResultPublisher(template, objectMapper, kafkaConfig);
    UUID orderId = UUID.randomUUID();
    TcaResultEntity entity = new TcaResultEntity();
    entity.setTcaId(UUID.randomUUID());
    entity.setOrderId(orderId);
    entity.setSymbol("AAPL");
    entity.setStrategy("VWAP");
    entity.setSide(Side.BUY);
    entity.setSlippageBps(new BigDecimal("2.7778"));
    entity.setComputedAt(Instant.parse("2026-04-20T09:40:00Z"));

    publisher.publish(entity);

    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(template).send(eq("analytics.tca"), keyCaptor.capture(), payloadCaptor.capture());
    assertThat(keyCaptor.getValue()).isEqualTo(orderId.toString());
    assertThat(payloadCaptor.getValue()).contains("\"slippageBps\":2.7778");
    assertThat(payloadCaptor.getValue()).contains("\"side\":\"BUY\"");
  }
}
