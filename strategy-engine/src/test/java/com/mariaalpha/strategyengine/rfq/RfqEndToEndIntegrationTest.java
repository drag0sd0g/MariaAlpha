package com.mariaalpha.strategyengine.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.strategyengine.model.DataSource;
import com.mariaalpha.strategyengine.model.EventType;
import com.mariaalpha.strategyengine.model.MarketTick;
import com.mariaalpha.strategyengine.model.Side;
import com.mariaalpha.strategyengine.publisher.SignalPublisher;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
public class RfqEndToEndIntegrationTest {

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:latest");

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("strategy-engine.rfq.order-manager-base-url", () -> "http://127.0.0.1:1");
    registry.add("strategy-engine.rfq.position-lookup-timeout-ms", () -> "100");
  }

  @Autowired private MockMvc mvc;
  @Autowired private MarketStateCache cache;
  @Autowired private ObjectMapper json;
  @MockitoSpyBean private SignalPublisher publisher;

  @Test
  void quoteThenAcceptPublishesOrderSignalToStrategySignalsTopic() throws Exception {
    cache.onTick(
        new MarketTick(
            "AAPL",
            Instant.now(),
            EventType.QUOTE,
            BigDecimal.ZERO,
            0L,
            new BigDecimal("178.00"),
            new BigDecimal("178.20"),
            100L,
            80L,
            0L,
            DataSource.SIMULATED,
            false));

    var quoteResp =
        mvc.perform(
                post("/api/rfq/quote")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new RfqQuoteRequest("AAPL", 100))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.symbol").value("AAPL"))
            .andReturn();
    var body = json.readValue(quoteResp.getResponse().getContentAsString(), RfqQuoteResponse.class);
    assertThat(body.bid().doubleValue()).isLessThan(body.ask().doubleValue());

    var props = new HashMap<String, Object>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "rfq-test-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    try (var consumer = new KafkaConsumer<String, String>(props)) {
      consumer.subscribe(List.of("strategy.signals"));

      mvc.perform(
              post("/api/rfq/accept")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      json.writeValueAsString(
                          new RfqAcceptRequest(body.quoteId(), Side.BUY, body.ask()))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("ACCEPTED"));

      Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(250))
          .until(
              () -> {
                var records = consumer.poll(Duration.ofMillis(200));
                for (var r : records) {
                  if (r.value().contains("RFQ") && r.value().contains("AAPL")) {
                    return true;
                  }
                }
                return false;
              });
    }
    Mockito.verify(publisher, Mockito.atLeastOnce())
        .publish(Mockito.argThat(s -> "RFQ".equals(s.strategyName())));
  }
}
