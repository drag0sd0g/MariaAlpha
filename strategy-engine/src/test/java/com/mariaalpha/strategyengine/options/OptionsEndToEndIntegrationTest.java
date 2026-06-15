package com.mariaalpha.strategyengine.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Spring-context integration test for the options pricing endpoints. Boots the full strategy-engine
 * context (Kafka via Testcontainers — required for the consumer/producer beans to wire up) and hits
 * {@code /api/options/{price,greeks,implied-volatility}} via MockMvc.
 *
 * <p>The endpoints themselves are stateless and don't touch Kafka — Testcontainers is here purely
 * so the rest of the context can start. The kafka topics created up front mirror the production
 * defaults so {@code @KafkaListener} startup doesn't crash with UNKNOWN_TOPIC_OR_PARTITION.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
public class OptionsEndToEndIntegrationTest {

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:latest");

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @BeforeAll
  static void createTopics() throws Exception {
    try (AdminClient admin =
        AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              java.util.List.of(
                  new NewTopic("market-data.ticks", 1, (short) 1),
                  new NewTopic("strategy.signals", 1, (short) 1)))
          .all()
          .get();
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Test
  void priceEndpointReturnsTextbookValueAndAllGreeks() throws Exception {
    var request =
        new OptionPricingRequest("AAPL", 42.0, 40.0, 0.5, 0.20, 0.10, 0.0, OptionType.CALL);
    var raw =
        mvc.perform(
                post("/api/options/price")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.symbol").value("AAPL"))
            .andExpect(jsonPath("$.type").value("CALL"))
            .andReturn();
    var body = json.readValue(raw.getResponse().getContentAsString(), OptionPricingResponse.class);
    assertThat(body.price()).isCloseTo(4.76, org.assertj.core.api.Assertions.within(0.01));
    assertThat(body.greeks().delta())
        .isCloseTo(0.7791, org.assertj.core.api.Assertions.within(0.001));
    assertThat(body.greeks().gamma())
        .isCloseTo(0.0498, org.assertj.core.api.Assertions.within(0.001));
    assertThat(body.greeks().vega())
        .isCloseTo(0.0879, org.assertj.core.api.Assertions.within(0.001));
    assertThat(body.greeks().theta())
        .isCloseTo(-0.01247, org.assertj.core.api.Assertions.within(0.001));
    assertThat(body.greeks().rho())
        .isCloseTo(0.1398, org.assertj.core.api.Assertions.within(0.001));
  }

  @Test
  void greeksOnlyEndpointReturnsJustGreeks() throws Exception {
    var request =
        new OptionPricingRequest("MSFT", 100.0, 95.0, 1.0, 0.30, 0.04, 0.02, OptionType.PUT);
    mvc.perform(
            post("/api/options/greeks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value("MSFT"))
        .andExpect(jsonPath("$.type").value("PUT"))
        .andExpect(jsonPath("$.greeks.delta").exists())
        .andExpect(jsonPath("$.greeks.gamma").exists())
        .andExpect(jsonPath("$.greeks.vega").exists())
        .andExpect(jsonPath("$.greeks.theta").exists())
        .andExpect(jsonPath("$.greeks.rho").exists());
  }

  @Test
  void priceEndpointReturns400OnNegativeSpot() throws Exception {
    var request =
        new OptionPricingRequest("AAPL", -100.0, 40.0, 0.5, 0.20, 0.10, 0.0, OptionType.CALL);
    mvc.perform(
            post("/api/options/price")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void priceEndpointReturns400OnZeroVolatility() throws Exception {
    var request =
        new OptionPricingRequest("AAPL", 100.0, 100.0, 0.5, 0.0, 0.05, 0.0, OptionType.CALL);
    mvc.perform(
            post("/api/options/price")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void impliedVolEndpointRoundTripsThroughHttp() throws Exception {
    var pricingReq =
        new OptionPricingRequest("AAPL", 100.0, 100.0, 0.5, 0.35, 0.04, 0.0, OptionType.CALL);
    var pricingResp =
        mvc.perform(
                post("/api/options/price")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(pricingReq)))
            .andExpect(status().isOk())
            .andReturn();
    var pricing =
        json.readValue(pricingResp.getResponse().getContentAsString(), OptionPricingResponse.class);

    var ivReq =
        new ImpliedVolatilityRequest(
            "AAPL", 100.0, 100.0, 0.5, 0.04, 0.0, OptionType.CALL, pricing.price());
    var ivResp =
        mvc.perform(
                post("/api/options/implied-volatility")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(ivReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.symbol").value("AAPL"))
            .andExpect(jsonPath("$.impliedVolatility").exists())
            .andReturn();
    var iv =
        json.readValue(ivResp.getResponse().getContentAsString(), ImpliedVolatilityResponse.class);
    assertThat(iv.impliedVolatility())
        .isCloseTo(0.35, org.assertj.core.api.Assertions.within(1e-4));
  }
}
