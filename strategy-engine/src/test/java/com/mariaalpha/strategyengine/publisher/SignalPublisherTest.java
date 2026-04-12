package com.mariaalpha.strategyengine.publisher;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.strategyengine.config.KafkaConfig;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class SignalPublisherTest {

  @SuppressWarnings("unchecked")
  @Test
  void publishSendsJsonToSignalsTopic() {
    var kafkaTemplate = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
    var objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    var config = new KafkaConfig("market-data.ticks", "strategy.signals");
    var publisher = new SignalPublisher(kafkaTemplate, objectMapper, config);
    var signal =
        new OrderSignal(
            "AAPL",
            Side.BUY,
            100,
            OrderType.LIMIT,
            new BigDecimal("178.54"),
            "VWAP",
            Instant.now());
    publisher.publish(signal);
    verify(kafkaTemplate).send(eq(config.signalsTopic()), eq("AAPL"), anyString());
  }
}
