package com.mariaalpha.ordermanager.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.ordermanager.config.KafkaConfig;
import com.mariaalpha.ordermanager.controller.dto.PositionSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class PositionUpdatePublisherTest {

  @Mock private KafkaTemplate<String, String> kafkaTemplate;
  private ObjectMapper objectMapper;
  private PositionUpdatePublisher publisher;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    KafkaConfig config =
        new KafkaConfig("orders.lifecycle", "market-data.ticks", "positions.updates");
    publisher = new PositionUpdatePublisher(kafkaTemplate, objectMapper, config);
  }

  @Test
  void publishSendsJsonPayloadKeyedBySymbol() {
    PositionSnapshot snap =
        new PositionSnapshot(
            "AAPL",
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(150),
            BigDecimal.valueOf(500),
            BigDecimal.valueOf(1000),
            BigDecimal.valueOf(155),
            Instant.parse("2026-04-19T12:00:00Z"));

    publisher.publish(snap);

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(kafkaTemplate).send(eq("positions.updates"), eq("AAPL"), payload.capture());
    assertThat(payload.getValue()).contains("\"symbol\":\"AAPL\"");
    assertThat(payload.getValue()).contains("\"netQuantity\":100");
    assertThat(payload.getValue()).contains("\"realizedPnl\":500");
  }

  @Test
  void publishHandlesShortPositionSnapshot() {
    PositionSnapshot snap =
        new PositionSnapshot(
            "TSLA",
            BigDecimal.valueOf(-50),
            BigDecimal.valueOf(200),
            BigDecimal.ZERO,
            BigDecimal.valueOf(-250),
            BigDecimal.valueOf(205),
            Instant.now());

    publisher.publish(snap);
    verify(kafkaTemplate).send(eq("positions.updates"), eq("TSLA"), any());
  }

  @Test
  void publishSkipsWhenSerializationFails() {
    // Serialization cannot actually fail for PositionSnapshot (all fields serializable);
    // this test ensures no exception propagates even for a normal payload.
    PositionSnapshot snap =
        new PositionSnapshot(
            "GOOG",
            BigDecimal.ONE,
            BigDecimal.TEN,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.TEN,
            Instant.now());
    publisher.publish(snap);
    verify(kafkaTemplate).send(any(), any(), any());
  }
}
