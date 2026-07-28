package com.mariaalpha.strategyengine.backtest.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mariaalpha.strategyengine.backtest.BacktestProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches real historical 1-minute bars from Alpaca's market-data REST API, paging through the
 * {@code next_page_token} cursor. This is the "real data in" step for the backtester; it is the
 * only component that requires network access and Alpaca credentials, so it is exercised in manual
 * / integration checks rather than in the deterministic unit suite (which drives the engine from
 * captured fixtures).
 */
@Component
public class AlpacaHistoricalClient {

  private static final Logger LOG = LoggerFactory.getLogger(AlpacaHistoricalClient.class);
  private static final int PAGE_LIMIT = 10000;

  private final RestClient restClient;
  private final String feed;
  private final boolean credentialsPresent;

  public AlpacaHistoricalClient(BacktestProperties properties, RestClient.Builder builder) {
    var alpaca = properties.alpaca();
    this.feed = alpaca.feed();
    this.credentialsPresent = alpaca.hasCredentials();
    this.restClient =
        builder
            .baseUrl(alpaca.baseUrl())
            .defaultHeader("APCA-API-KEY-ID", nullToEmpty(alpaca.apiKeyId()))
            .defaultHeader("APCA-API-SECRET-KEY", nullToEmpty(alpaca.apiSecretKey()))
            .build();
  }

  public boolean hasCredentials() {
    return credentialsPresent;
  }

  /**
   * Returns every 1-minute bar Alpaca has for {@code symbol} in {@code [from, to)}, ordered oldest
   * first.
   */
  public List<MinuteBar> fetchMinuteBars(String symbol, Instant from, Instant to) {
    var bars = new ArrayList<MinuteBar>();
    String pageToken = null;
    do {
      final String token = pageToken;
      var page =
          restClient
              .get()
              .uri(
                  uri ->
                      uri.path("/v2/stocks/{symbol}/bars")
                          .queryParam("timeframe", "1Min")
                          .queryParam("start", from.toString())
                          .queryParam("end", to.toString())
                          .queryParam("limit", PAGE_LIMIT)
                          .queryParam("adjustment", "raw")
                          .queryParam("feed", feed)
                          .queryParamIfPresent("page_token", Optional.ofNullable(token))
                          .build(symbol))
              .retrieve()
              .body(BarsPage.class);
      if (page == null) {
        break;
      }
      for (var bar : page.bars()) {
        bars.add(
            new MinuteBar(
                symbol,
                bar.timestamp(),
                bar.open(),
                bar.high(),
                bar.low(),
                bar.close(),
                bar.volume(),
                bar.vwap()));
      }
      pageToken = page.nextPageToken();
    } while (pageToken != null && !pageToken.isBlank());

    LOG.info("Fetched {} 1-min bars for {} in [{}, {})", bars.size(), symbol, from, to);
    return bars;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record BarsPage(
      @JsonProperty("bars") List<RawBar> bars,
      @JsonProperty("symbol") String symbol,
      @JsonProperty("next_page_token") String nextPageToken) {
    BarsPage {
      bars = bars == null ? List.of() : List.copyOf(bars);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record RawBar(
      @JsonProperty("t") Instant timestamp,
      @JsonProperty("o") BigDecimal open,
      @JsonProperty("h") BigDecimal high,
      @JsonProperty("l") BigDecimal low,
      @JsonProperty("c") BigDecimal close,
      @JsonProperty("v") long volume,
      @JsonProperty("vw") BigDecimal vwap) {}
}
