package com.mariaalpha.marketdatagateway.repository;

import com.mariaalpha.marketdatagateway.model.HistoricalBar;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HistoricalBarRepository {

  private static final String UPSERT_SQL =
      """
            INSERT INTO market_data_daily
              (symbol, bar_date, open_price, high_price, low_price, close_price, volume, vwap)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, bar_date) DO UPDATE SET
              open_price   = EXCLUDED.open_price,
              high_price   = EXCLUDED.high_price,
              low_price    = EXCLUDED.low_price,
              close_price  = EXCLUDED.close_price,
              volume       = EXCLUDED.volume,
              vwap         = EXCLUDED.vwap
            """;

  private final JdbcTemplate jdbcTemplate;

  public HistoricalBarRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void upsert(HistoricalBar bar) {
    jdbcTemplate.update(
        UPSERT_SQL,
        bar.symbol(),
        bar.barDate(),
        bar.open(),
        bar.high(),
        bar.low(),
        bar.close(),
        bar.volume(),
        bar.vwap());
  }

  public void upsertAll(List<HistoricalBar> bars) {
    jdbcTemplate.batchUpdate(
        UPSERT_SQL,
        bars,
        bars.size(),
        (ps, bar) -> {
          ps.setString(1, bar.symbol());
          ps.setObject(2, bar.barDate());
          ps.setBigDecimal(3, bar.open());
          ps.setBigDecimal(4, bar.high());
          ps.setBigDecimal(5, bar.low());
          ps.setBigDecimal(6, bar.close());
          ps.setLong(7, bar.volume());
          ps.setBigDecimal(8, bar.vwap());
        });
  }

  public int countBySymbol(String symbol) {
    var count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM market_data_daily WHERE symbol = ?", Integer.class, symbol);
    return count != null ? count : 0;
  }
}
