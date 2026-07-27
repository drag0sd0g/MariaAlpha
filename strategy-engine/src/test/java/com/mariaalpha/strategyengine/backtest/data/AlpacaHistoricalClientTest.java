package com.mariaalpha.strategyengine.backtest.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mariaalpha.strategyengine.backtest.BacktestProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AlpacaHistoricalClientTest {

  // A captured-shape Alpaca bars response (including the unmapped "n" trade-count field).
  private static final String PAGE_1 =
      """
      {"bars":[
        {"t":"2026-03-24T14:30:00Z","o":100.0,"h":100.5,
         "l":99.5,"c":100.2,"v":1000,"n":42,"vw":100.1},
        {"t":"2026-03-24T14:31:00Z","o":100.2,"h":100.8,
         "l":100.1,"c":100.7,"v":1500,"n":55,"vw":100.4}
      ],"symbol":"AAPL","next_page_token":"PAGE2"}
      """;
  private static final String PAGE_2 =
      """
      {"bars":[
        {"t":"2026-03-24T14:32:00Z","o":100.7,"h":101.0,
         "l":100.6,"c":100.9,"v":800,"n":30,"vw":100.8}
      ],"symbol":"AAPL","next_page_token":null}
      """;

  @Test
  void fetchesAndPaginatesMinuteBarsWithAuthHeaders() {
    var mapper =
        JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    var builder =
        RestClient.builder()
            .messageConverters(
                converters -> {
                  converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                  converters.add(new MappingJackson2HttpMessageConverter(mapper));
                });
    var server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo(containsString("/v2/stocks/AAPL/bars")))
        .andExpect(requestTo(containsString("timeframe=1Min")))
        .andExpect(header("APCA-API-KEY-ID", "test-key"))
        .andExpect(header("APCA-API-SECRET-KEY", "test-secret"))
        .andRespond(withSuccess(PAGE_1, MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(containsString("page_token=PAGE2")))
        .andRespond(withSuccess(PAGE_2, MediaType.APPLICATION_JSON));

    var properties =
        new BacktestProperties(
            new BacktestProperties.Alpaca(
                "https://data.alpaca.markets", "test-key", "test-secret", "iex"),
            0,
            1.0,
            2.0,
            null);
    var client = new AlpacaHistoricalClient(properties, builder);

    var bars =
        client.fetchMinuteBars(
            "AAPL", Instant.parse("2026-03-24T00:00:00Z"), Instant.parse("2026-03-25T00:00:00Z"));

    server.verify();
    assertThat(bars).hasSize(3);
    assertThat(bars.get(0).timestamp()).isEqualTo(Instant.parse("2026-03-24T14:30:00Z"));
    assertThat(bars.get(0).close()).isEqualByComparingTo("100.2");
    assertThat(bars.get(0).volume()).isEqualTo(1000);
    assertThat(bars.get(2).close()).isEqualByComparingTo("100.9");
    assertThat(client.hasCredentials()).isTrue();
  }
}
