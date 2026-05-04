package com.mariaalpha.marketdatagateway.adapter;

import com.mariaalpha.marketdatagateway.config.SimulatedMarketDataConfig;
import com.mariaalpha.marketdatagateway.model.BarTimeframe;
import com.mariaalpha.marketdatagateway.model.DataSource;
import com.mariaalpha.marketdatagateway.model.EventType;
import com.mariaalpha.marketdatagateway.model.HistoricalBar;
import com.mariaalpha.marketdatagateway.model.MarketTick;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@Profile("simulated")
public class SimulatedMarketDataAdapter implements MarketDataAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(SimulatedMarketDataAdapter.class);
  private static final Pattern CSV_DELIMITER = Pattern.compile(",");

  private final SimulatedMarketDataConfig config;
  private final ResourceLoader resourceLoader;
  private List<MarketTick> ticks;
  private Set<String> subscribedSymbols;
  private volatile boolean connected;

  public SimulatedMarketDataAdapter(
      SimulatedMarketDataConfig config, ResourceLoader resourceLoader) {
    this.config = config;
    this.resourceLoader = resourceLoader;
  }

  /** Loads tick data from the CSV and subscribes to the given symbols. */
  @Override
  public void connect(List<String> symbols) {
    this.subscribedSymbols = Set.copyOf(symbols);
    this.ticks = loadTicks();
    this.connected = true;
    LOG.info("Loaded {} ticks from {}, subscribed to {}", ticks.size(), config.csvPath(), symbols);
  }

  /** Stops the adapter; any in-progress replay will terminate. */
  @Override
  public void disconnect() {
    this.connected = false;
    LOG.info("disconnected simulated market data adapter");
  }

  @Override
  public boolean isConnected() {
    return connected;
  }

  @Override
  public List<String> subscribedSymbols() {
    return subscribedSymbols == null ? List.of() : List.copyOf(subscribedSymbols);
  }

  /**
   * Returns a Flux that replays ticks for subscribed symbols, paced by the configured speed
   * multiplier (0 = no delay).
   */
  @Override
  public Flux<MarketTick> streamTicks() {
    if (!connected || ticks == null) {
      return Flux.empty();
    }
    var filtered = filterBySubscribedSymbols();
    if (filtered.isEmpty()) {
      return Flux.empty();
    }
    Flux<MarketTick> singlePass =
        config.speedMultiplier() <= 0 ? Flux.fromIterable(filtered) : replayWithDelay(filtered);
    if (config.loopDelayMs() <= 0) {
      return singlePass;
    }
    // Loop the CSV replay with a pause between iterations so strategies configured
    // after startup still receive ticks. Stops immediately when disconnect() is called.
    return singlePass.repeatWhen(
        n ->
            n.delayElements(Duration.ofMillis(config.loopDelayMs()))
                .takeWhile(ignored -> connected));
  }

  /** Returns only ticks whose symbol matches the subscription list. */
  private List<MarketTick> filterBySubscribedSymbols() {
    return ticks.stream().filter(tick -> subscribedSymbols.contains(tick.symbol())).toList();
  }

  /** Not supported — historical bars are fetched via the Alpaca adapter. */
  @Override
  public List<HistoricalBar> getHistoricalBars(
      String symbol, LocalDate from, LocalDate to, BarTimeframe timeframe) {
    throw new UnsupportedOperationException("Historical bars not supported by simulated adapter");
  }

  /** Reads and parses the CSV file into an ordered list of ticks. */
  private List<MarketTick> loadTicks() {
    var resource = resourceLoader.getResource(config.csvPath());
    try (var reader =
        new BufferedReader(
            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
      return reader
          .lines()
          .skip(1)
          .map(String::trim)
          .filter(line -> !line.isEmpty())
          .map(this::parseLine)
          .toList();
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to load CSV: " + config.csvPath(), ex);
    }
  }

  /** Parses a single CSV row into a MarketTick. */
  private MarketTick parseLine(String line) {
    var parts = CSV_DELIMITER.split(line, -1);
    if (parts.length != 10) {
      throw new IllegalArgumentException("Expected 10 fields, got " + parts.length + ": " + line);
    }
    return new MarketTick(
        parts[0].trim(),
        Instant.parse(parts[1].trim()),
        EventType.valueOf(parts[2].trim()),
        new BigDecimal((parts[3].trim())),
        Long.parseLong(parts[4].trim()),
        new BigDecimal(parts[5].trim()),
        new BigDecimal(parts[6].trim()),
        Long.parseLong(parts[7].trim()),
        Long.parseLong(parts[8].trim()),
        Long.parseLong(parts[9].trim()),
        DataSource.SIMULATED,
        false);
  }

  /** Emits ticks with inter-tick delays derived from CSV timestamps. */
  private Flux<MarketTick> replayWithDelay(List<MarketTick> filtered) {
    return Flux.create(
        sink -> {
          for (var i = 0; i < filtered.size(); i++) {
            if (sink.isCancelled() || !connected) {
              break;
            }
            sink.next(filtered.get(i));
            sleepBetweenTicks(filtered, i);
          }
          sink.complete();
        });
  }

  /** Sleeps for the scaled time delta between consecutive ticks. */
  private void sleepBetweenTicks(List<MarketTick> filtered, int index) {
    if (index >= filtered.size() - 1) {
      return;
    }
    var delta =
        Duration.between(filtered.get(index).timestamp(), filtered.get(index + 1).timestamp());
    var delayMs = (long) (delta.toMillis() / config.speedMultiplier());
    if (delayMs > 0) {
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
