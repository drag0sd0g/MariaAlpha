package com.mariaalpha.marketdatagateway.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.RateLimiter;
import com.mariaalpha.marketdatagateway.adapter.alpaca.AlpacaMarketDataAdapter;
import com.mariaalpha.marketdatagateway.config.AlpacaMarketDataConfig;
import com.mariaalpha.marketdatagateway.config.BackfillConfig;
import com.mariaalpha.marketdatagateway.repository.HistoricalBarRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
class BackfillIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private MockWebServer mockAlpaca;
  private JdbcTemplate jdbcTemplate;
  private BackfillService backfillService;
  private HistoricalBarRepository repository;

  @BeforeEach
  void setUp() throws Exception {
    mockAlpaca = new MockWebServer();
    mockAlpaca.start();

    var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute(
        """
                CREATE TABLE IF NOT EXISTS market_data_daily (
                  symbol VARCHAR(16) NOT NULL,
                  bar_date DATE NOT NULL,
                  open_price DECIMAL(18,8),
                  high_price DECIMAL(18,8),
                  low_price DECIMAL(18,8),
                  close_price DECIMAL(18,8),
                  volume BIGINT,
                  vwap DECIMAL(18,8),
                  PRIMARY KEY (symbol, bar_date)
                )
                """);
    jdbcTemplate.execute("DELETE FROM market_data_daily");

    var config =
        new AlpacaMarketDataConfig(
            "wss://unused", mockAlpaca.url("/").toString(), "test-key", "test-secret");
    var adapter = new AlpacaMarketDataAdapter(config, new SimpleMeterRegistry());
    repository = new HistoricalBarRepository(jdbcTemplate);
    var backfillConfig = new BackfillConfig(60);
    backfillService =
        new BackfillService(
            adapter, repository, backfillConfig, RateLimiter.create(Double.MAX_VALUE));
  }

  @AfterEach
  void tearDown() throws Exception {
    mockAlpaca.shutdown();
  }

  @Test
  void backfillFetchesFromApiAndPersistsToDatabase() {
    var responseJson =
        """
                {
                  "bars": [
                    {"t":"2026-01-02T05:00:00Z",
                     "o":185.59,"h":186.10,"l":184.27,
                     "c":185.64,"v":45234567,
                     "n":123456,"vw":185.42},
                    {"t":"2026-01-03T05:00:00Z",
                     "o":186.00,"h":187.50,"l":185.80,
                     "c":187.20,"v":38000000,
                     "n":100000,"vw":186.80}
                  ],
                  "symbol": "AAPL",
                  "next_page_token": null
                }
                """;
    mockAlpaca.enqueue(
        new MockResponse().setBody(responseJson).setHeader("Content-Type", "application/json"));

    backfillService.backfill(List.of("AAPL"));

    assertThat(repository.countBySymbol("AAPL")).isEqualTo(2);

    var closePrice =
        jdbcTemplate.queryForObject(
            "SELECT close_price FROM market_data_daily WHERE symbol = ? AND bar_date = ?",
            BigDecimal.class,
            "AAPL",
            LocalDate.of(2026, 1, 2));
    assertThat(closePrice).isEqualByComparingTo(new BigDecimal("185.64"));
  }

  @Test
  void backfillIsIdempotentOnRerun() {
    var responseJson =
        """
                {
                  "bars": [
                    {"t":"2026-01-02T05:00:00Z",
                     "o":185.59,"h":186.10,"l":184.27,
                     "c":185.64,"v":45234567,
                     "n":123456,"vw":185.42}
                  ],
                  "symbol": "AAPL",
                  "next_page_token": null
                }
                """;
    mockAlpaca.enqueue(
        new MockResponse().setBody(responseJson).setHeader("Content-Type", "application/json"));
    mockAlpaca.enqueue(
        new MockResponse().setBody(responseJson).setHeader("Content-Type", "application/json"));

    backfillService.backfill(List.of("AAPL"));
    backfillService.backfill(List.of("AAPL"));

    assertThat(repository.countBySymbol("AAPL")).isEqualTo(1);
  }
}
