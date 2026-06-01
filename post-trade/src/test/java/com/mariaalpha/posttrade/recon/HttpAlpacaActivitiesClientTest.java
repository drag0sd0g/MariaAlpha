package com.mariaalpha.posttrade.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.posttrade.model.Side;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpAlpacaActivitiesClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpAlpacaActivitiesClient client =
      new HttpAlpacaActivitiesClient(
          new ReconConfig.Alpaca("http://stub", "k", "s", 1000, "FILL"), objectMapper);

  @Test
  void parseActivitiesHandlesEmptyArray() {
    assertThat(client.parseActivities("[]")).isEmpty();
  }

  @Test
  void parseActivitiesMapsExpectedFields() {
    String body =
        "[{\"id\":\"act-1\",\"order_id\":\"alpaca-1\","
            + "\"client_order_id\":\"c-1\",\"symbol\":\"AAPL\","
            + "\"side\":\"buy\",\"price\":\"180.05\",\"qty\":\"100\","
            + "\"transaction_time\":\"2026-06-01T15:30:00Z\"}]";
    List<ExternalFill> result = client.parseActivities(body);
    assertThat(result).hasSize(1);
    var f = result.get(0);
    assertThat(f.externalFillId()).isEqualTo("act-1");
    assertThat(f.exchangeOrderId()).isEqualTo("alpaca-1");
    assertThat(f.clientOrderId()).isEqualTo("c-1");
    assertThat(f.symbol()).isEqualTo("AAPL");
    assertThat(f.side()).isEqualTo(Side.BUY);
    assertThat(f.price()).isEqualByComparingTo("180.05");
    assertThat(f.quantity()).isEqualByComparingTo("100");
  }

  @Test
  void parseActivitiesMapsSellShortAsSell() {
    String body =
        "[{\"id\":\"act-1\",\"order_id\":\"alpaca-1\","
            + "\"client_order_id\":\"c-1\",\"symbol\":\"AAPL\","
            + "\"side\":\"sell_short\",\"price\":\"180.05\",\"qty\":\"100\","
            + "\"transaction_time\":\"2026-06-01T15:30:00Z\"}]";
    var result = client.parseActivities(body);
    assertThat(result.get(0).side()).isEqualTo(Side.SELL);
  }

  @Test
  void parseActivitiesSkipsMalformedNodes() {
    String body =
        "[{\"id\":\"act-1\",\"order_id\":\"alpaca-1\","
            + "\"client_order_id\":\"c-1\",\"symbol\":\"AAPL\","
            + "\"side\":\"buy\",\"price\":\"180.05\",\"qty\":\"100\","
            + "\"transaction_time\":\"2026-06-01T15:30:00Z\"},"
            + "{\"id\":\"act-2\"}]"; // missing required fields
    var result = client.parseActivities(body);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalFillId()).isEqualTo("act-1");
  }

  @Test
  void parseActivitiesOnInvalidJsonThrows() {
    assertThatThrownBy(() -> client.parseActivities("not-json"))
        .isInstanceOf(AlpacaActivitiesException.class);
  }

  @Test
  void missingCredsThrowsBeforeNetwork() {
    var noCreds =
        new HttpAlpacaActivitiesClient(
            new ReconConfig.Alpaca("http://stub", "", "", 1000, "FILL"), objectMapper);
    assertThatThrownBy(() -> noCreds.activitiesForDate(java.time.LocalDate.now(), List.of()))
        .isInstanceOf(AlpacaActivitiesException.class)
        .hasMessageContaining("credentials not configured");
  }
}
