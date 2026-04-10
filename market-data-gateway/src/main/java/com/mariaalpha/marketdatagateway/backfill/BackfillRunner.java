package com.mariaalpha.marketdatagateway.backfill;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("alpaca")
public class BackfillRunner implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(BackfillRunner.class);

  private final BackfillService backfillService;
  private final List<String> symbols;

  public BackfillRunner(
      BackfillService backfillService, @Value("${market-data.symbols}") List<String> symbols) {
    this.backfillService = backfillService;
    this.symbols = List.copyOf(symbols);
  }

  @Override
  public void run(ApplicationArguments args) {
    LOG.info("Running historical bar backfill for symbols: {}", symbols);
    backfillService.backfill(symbols);
  }
}
