package com.mariaalpha.marketdatagateway.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarketTickJsonTest {

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Test
  void serializesToCamelCaseJson() throws Exception {
    var tick =
        new MarketTick(
            "AAPL",
            Instant.parse("2026-03-24T14:30:00.123Z"),
            EventType.TRADE,
            new BigDecimal("178.52"),
            100L,
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            200L,
            150L,
            12345678L,
            DataSource.ALPACA);

    var json = objectMapper.writeValueAsString(tick);

    assertThat(json).contains("\"symbol\":\"AAPL\"");
    assertThat(json).contains("\"timestamp\":\"2026-03-24T14:30:00.123Z\"");
    assertThat(json).contains("\"eventType\":\"TRADE\"");
    assertThat(json).contains("\"price\":178.52");
    assertThat(json).contains("\"size\":100");
    assertThat(json).contains("\"bidPrice\":178.50");
    assertThat(json).contains("\"askPrice\":178.54");
    assertThat(json).contains("\"bidSize\":200");
    assertThat(json).contains("\"askSize\":150");
    assertThat(json).contains("\"cumulativeVolume\":12345678");
    assertThat(json).contains("\"source\":\"ALPACA\"");
  }

  @Test
  void timestampSerializesAsIso8601() throws Exception {
    var tick =
        new MarketTick(
            "MSFT",
            Instant.parse("2026-03-24T14:30:00.456Z"),
            EventType.QUOTE,
            BigDecimal.ZERO,
            0L,
            new BigDecimal("415.18"),
            new BigDecimal("415.24"),
            100L,
            80L,
            500000L,
            DataSource.ALPACA);

    var json = objectMapper.writeValueAsString(tick);

    assertThat(json).contains("\"timestamp\":\"2026-03-24T14:30:00.456Z\"");
    assertThat(json).doesNotContain("1711287000");
  }

  @Test
  void roundTripsCorrectly() throws Exception {
    var original =
        new MarketTick(
            "AAPL",
            Instant.parse("2026-03-24T14:30:00.123Z"),
            EventType.TRADE,
            new BigDecimal("178.52"),
            100L,
            new BigDecimal("178.50"),
            new BigDecimal("178.54"),
            200L,
            150L,
            12345678L,
            DataSource.ALPACA);

    var json = objectMapper.writeValueAsString(original);
    var deserialized = objectMapper.readValue(json, MarketTick.class);

    assertThat(deserialized).isEqualTo(original);
  }
}
