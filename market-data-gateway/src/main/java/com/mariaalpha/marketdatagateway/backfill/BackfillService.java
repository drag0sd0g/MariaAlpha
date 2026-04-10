package com.mariaalpha.marketdatagateway.backfill;

import com.google.common.util.concurrent.RateLimiter;
import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import com.mariaalpha.marketdatagateway.config.BackfillConfig;
import com.mariaalpha.marketdatagateway.model.BarTimeframe;
import com.mariaalpha.marketdatagateway.repository.HistoricalBarRepository;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BackfillService {

  private static final Logger LOG = LoggerFactory.getLogger(BackfillService.class);
  private static final double REQUESTS_PER_SECOND = 200.0 / 60.0;

  private final MarketDataAdapter adapter;
  private final HistoricalBarRepository repository;
  private final BackfillConfig config;
  private final RateLimiter rateLimiter;

  public BackfillService(
      MarketDataAdapter adapter, HistoricalBarRepository repository, BackfillConfig config) {
    this.adapter = adapter;
    this.repository = repository;
    this.config = config;
    this.rateLimiter = RateLimiter.create(REQUESTS_PER_SECOND);
  }

  BackfillService(
      MarketDataAdapter adapter,
      HistoricalBarRepository repository,
      BackfillConfig config,
      RateLimiter rateLimiter) {
    this.adapter = adapter;
    this.repository = repository;
    this.config = config;
    this.rateLimiter = rateLimiter;
  }

  public void backfill(List<String> symbols) {
    var to = LocalDate.now();
    var from = to.minusDays(config.lookbackDays());
    LOG.info("Starting backfill for {} symbols from {} to {}", symbols.size(), from, to);

    for (var symbol : symbols) {
      try {
        rateLimiter.acquire();
        var bars = adapter.getHistoricalBars(symbol, from, to, BarTimeframe.ONE_DAY);
        repository.upsertAll(bars);
        LOG.info("Persisted {} bars for {}", bars.size(), symbol);
      } catch (Exception e) {
        LOG.error("Failed to backfill {}", symbol, e);
      }
    }

    LOG.info("Backfill complete");
  }
}
