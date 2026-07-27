package com.mariaalpha.strategyengine.backtest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the backtest harness, bound from {@code strategy-engine.backtest.*}.
 *
 * @param alpaca Alpaca historical-data credentials and endpoint
 * @param lookbackDays default number of calendar days of history to replay when a request omits an
 *     explicit range
 * @param slippageBps modelled slippage applied against the touch on every fill, in basis points
 * @param spreadBps modelled bid/ask spread synthesized around each historical price, in basis
 *     points
 * @param reportDir directory (relative to the service working dir) where HTML reports are written
 */
@ConfigurationProperties(prefix = "strategy-engine.backtest")
public record BacktestProperties(
    Alpaca alpaca, int lookbackDays, double slippageBps, double spreadBps, String reportDir) {

  public BacktestProperties {
    if (alpaca == null) {
      alpaca = new Alpaca(null, null, null, null);
    }
    if (lookbackDays <= 0) {
      lookbackDays = 5;
    }
    if (spreadBps <= 0.0) {
      spreadBps = 2.0;
    }
    if (reportDir == null || reportDir.isBlank()) {
      reportDir = "build/reports/backtest";
    }
  }

  /**
   * Alpaca market-data API access for historical bars.
   *
   * @param baseUrl data API base URL (defaults to the public endpoint)
   * @param apiKeyId {@code APCA-API-KEY-ID}
   * @param apiSecretKey {@code APCA-API-SECRET-KEY}
   * @param feed data feed to request (free tier: {@code iex})
   */
  public record Alpaca(String baseUrl, String apiKeyId, String apiSecretKey, String feed) {
    public Alpaca {
      if (baseUrl == null || baseUrl.isBlank()) {
        baseUrl = "https://data.alpaca.markets";
      }
      if (feed == null || feed.isBlank()) {
        feed = "iex";
      }
    }

    public boolean hasCredentials() {
      return apiKeyId != null
          && !apiKeyId.isBlank()
          && apiSecretKey != null
          && !apiSecretKey.isBlank();
    }
  }
}
