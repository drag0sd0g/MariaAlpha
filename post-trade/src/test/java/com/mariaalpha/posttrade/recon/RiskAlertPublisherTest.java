package com.mariaalpha.posttrade.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class RiskAlertPublisherTest {

  @SuppressWarnings("unchecked")
  @Test
  void publishesJsonAlertOnConfiguredTopic() throws Exception {
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    var publisher = RiskAlertPublisher.forTest(kafka, mapper, "analytics.risk-alerts");

    var breakRow =
        new ReconciliationBreak(
            LocalDate.of(2026, 6, 1),
            UUID.fromString("00000000-0000-0000-0000-000000000099"),
            ReconciliationBreak.BreakType.MISSING_FILL,
            ReconciliationBreak.Severity.HIGH,
            "AAPL",
            "the description",
            null,
            new BigDecimal("100"),
            null,
            new BigDecimal("180.05"),
            new BigDecimal("18005.00"));
    publisher.publishBreak(breakRow);

    ArgumentCaptor<String> topicCap = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
    verify(kafka).send(topicCap.capture(), keyCap.capture(), payloadCap.capture());

    assertThat(topicCap.getValue()).isEqualTo("analytics.risk-alerts");
    assertThat(keyCap.getValue()).isEqualTo("AAPL");
    JsonNode payload = mapper.readTree(payloadCap.getValue());
    assertThat(payload.get("alertType").asText()).isEqualTo("RECON_BREAK");
    assertThat(payload.get("breakType").asText()).isEqualTo("MISSING_FILL");
    assertThat(payload.get("severity").asText()).isEqualTo("HIGH");
    assertThat(payload.get("symbol").asText()).isEqualTo("AAPL");
    assertThat(payload.get("orderId").asText()).isEqualTo("00000000-0000-0000-0000-000000000099");
    assertThat(payload.get("notional").decimalValue()).isEqualByComparingTo("18005.00");
  }
}
