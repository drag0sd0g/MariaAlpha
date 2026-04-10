package com.mariaalpha.marketdatagateway.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.marketdatagateway.model.HistoricalBar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
class HistoricalBarRepositoryTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private HistoricalBarRepository repository;
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
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
    repository = new HistoricalBarRepository(jdbcTemplate);
  }

  @Test
  void upsertInsertsNewBar() {
    var bar =
        bar(
            "AAPL",
            LocalDate.of(2026, 1, 2),
            "185.59",
            "186.10",
            "184.27",
            "185.64",
            45234567L,
            "185.42");

    repository.upsert(bar);

    var count = repository.countBySymbol("AAPL");
    assertThat(count).isEqualTo(1);
  }

  @Test
  void upsertUpdatesExistingBar() {
    var original =
        bar(
            "AAPL",
            LocalDate.of(2026, 1, 2),
            "185.59",
            "186.10",
            "184.27",
            "185.64",
            45234567L,
            "185.42");
    var updated =
        bar(
            "AAPL",
            LocalDate.of(2026, 1, 2),
            "190.00",
            "191.00",
            "189.00",
            "190.50",
            50000000L,
            "190.25");

    repository.upsert(original);
    repository.upsert(updated);

    var count = repository.countBySymbol("AAPL");
    assertThat(count).isEqualTo(1);

    var closePrice =
        jdbcTemplate.queryForObject(
            "SELECT close_price FROM market_data_daily WHERE symbol = ? AND bar_date = ?",
            BigDecimal.class,
            "AAPL",
            LocalDate.of(2026, 1, 2));
    assertThat(closePrice).isEqualByComparingTo(new BigDecimal("190.50"));
  }

  @Test
  void upsertAllBatchInsertsMultipleBars() {
    var bars =
        List.of(
            bar(
                "AAPL",
                LocalDate.of(2026, 1, 2),
                "185.59",
                "186.10",
                "184.27",
                "185.64",
                45234567L,
                "185.42"),
            bar(
                "AAPL",
                LocalDate.of(2026, 1, 3),
                "186.00",
                "187.50",
                "185.80",
                "187.20",
                38000000L,
                "186.80"),
            bar(
                "MSFT",
                LocalDate.of(2026, 1, 2),
                "415.00",
                "416.00",
                "414.00",
                "415.50",
                20000000L,
                "415.25"));

    repository.upsertAll(bars);

    assertThat(repository.countBySymbol("AAPL")).isEqualTo(2);
    assertThat(repository.countBySymbol("MSFT")).isEqualTo(1);
  }

  @Test
  void upsertAllIsIdempotent() {
    var bars =
        List.of(
            bar(
                "AAPL",
                LocalDate.of(2026, 1, 2),
                "185.59",
                "186.10",
                "184.27",
                "185.64",
                45234567L,
                "185.42"));

    repository.upsertAll(bars);
    repository.upsertAll(bars);

    assertThat(repository.countBySymbol("AAPL")).isEqualTo(1);
  }

  private static HistoricalBar bar(
      String symbol,
      LocalDate date,
      String open,
      String high,
      String low,
      String close,
      long volume,
      String vwap) {
    return new HistoricalBar(
        symbol,
        date,
        new BigDecimal(open),
        new BigDecimal(high),
        new BigDecimal(low),
        new BigDecimal(close),
        volume,
        new BigDecimal(vwap));
  }
}
