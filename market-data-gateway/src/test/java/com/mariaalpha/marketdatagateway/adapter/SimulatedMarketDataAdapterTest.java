package com.mariaalpha.marketdatagateway.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mariaalpha.marketdatagateway.config.SimulatedMarketDataConfig;
import com.mariaalpha.marketdatagateway.model.BarTimeframe;
import com.mariaalpha.marketdatagateway.model.DataSource;
import com.mariaalpha.marketdatagateway.model.EventType;
import com.mariaalpha.marketdatagateway.model.MarketTick;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import reactor.test.StepVerifier;

class SimulatedMarketDataAdapterTest {

  private static final String TEST_CSV = "classpath:simulated/test-data.csv";
  private static final String MALFORMED_CSV = "classpath:simulated/malformed.csv";

  private ResourceLoader resourceLoader;

  @BeforeEach
  void setUp() {
    resourceLoader = new DefaultResourceLoader();
  }

  private SimulatedMarketDataAdapter createAdapter(String csvPath, double speed) {
    var config = new SimulatedMarketDataConfig(csvPath, speed, 0L);
    return new SimulatedMarketDataAdapter(config, resourceLoader);
  }

  @Test
  void streamsAllTicksWithNoDelay() {
    var adapter = createAdapter(TEST_CSV, 0.0);
    adapter.connect(List.of("AAPL", "MSFT"));

    StepVerifier.create(adapter.streamTicks()).expectNextCount(5).verifyComplete();
  }

  @Test
  void ticksEmittedInCsvOrder() {
    var adapter = createAdapter(TEST_CSV, 0.0);
    adapter.connect(List.of("AAPL", "MSFT"));

    StepVerifier.create(adapter.streamTicks().map(MarketTick::symbol))
        .expectNext("AAPL", "AAPL", "AAPL", "MSFT", "MSFT")
        .verifyComplete();
  }

  @Test
  void allTicksHaveSimulatedSource() {
    var adapter = createAdapter(TEST_CSV, 0.0);
    adapter.connect(List.of("AAPL", "MSFT"));

    StepVerifier.create(adapter.streamTicks())
        .thenConsumeWhile(tick -> tick.source() == DataSource.SIMULATED)
        .verifyComplete();
  }

  @Test
  void firstTickHasCorrectFieldValues() {
    var adapter = createAdapter(TEST_CSV, 0.0);
    adapter.connect(List.of("AAPL"));

    StepVerifier.create(adapter.streamTicks().next())
        .assertNext(
            tick -> {
              assertThat(tick.symbol()).isEqualTo("AAPL");
              assertThat(tick.timestamp()).isEqualTo(Instant.parse("2026-03-24T14:30:00.000Z"));
              assertThat(tick.eventType()).isEqualTo(EventType.TRADE);
              assertThat(tick.price()).isEqualByComparingTo(new BigDecimal("178.50"));
              assertThat(tick.size()).isEqualTo(100L);
              assertThat(tick.bidPrice()).isEqualByComparingTo(new BigDecimal("178.48"));
              assertThat(tick.askPrice()).isEqualByComparingTo(new BigDecimal("178.52"));
              assertThat(tick.bidSize()).isEqualTo(200L);
              assertThat(tick.askSize()).isEqualTo(150L);
              assertThat(tick.cumulativeVolume()).isEqualTo(1000000L);
              assertThat(tick.source()).isEqualTo(DataSource.SIMULATED);
            })
        .verifyComplete();
  }

  @Test
  void filtersToSingleSymbol() {
    var adapter = createAdapter(TEST_CSV, 0.0);
    adapter.connect(List.of("AAPL"));

    StepVerifier.create(adapter.streamTicks())
        .thenConsumeWhile(tick -> tick.symbol().equals("AAPL"))
        .verifyComplete();
  }

  @Test
  void filtersToSingleSymbolCount() {
    var adapter = createAdapter(TEST_CSV, 0.0);
    adapter.connect(List.of("AAPL"));

    StepVerifier.create(adapter.streamTicks()).expectNextCount(3).verifyComplete();
  }

  @Test
  void emptyFluxWhenNoSymbolsMatch() {
    var adapter = createAdapter(TEST_CSV, 0.0);
    adapter.connect(List.of("GOOG"));

    StepVerifier.create(adapter.streamTicks()).verifyComplete();
  }

  @Test
  void emptyFluxWhenNotConnected() {
    var adapter = createAdapter(TEST_CSV, 0.0);

    StepVerifier.create(adapter.streamTicks()).verifyComplete();
  }

  @Test
  void emptyFluxAfterDisconnect() {
    var adapter = createAdapter(TEST_CSV, 0.0);
    adapter.connect(List.of("AAPL"));
    adapter.disconnect();

    StepVerifier.create(adapter.streamTicks()).verifyComplete();
  }

  @Test
  void speedMultiplierZeroEmitsWithoutDelay() {
    var adapter = createAdapter(TEST_CSV, 0.0);
    adapter.connect(List.of("AAPL", "MSFT"));

    var start = System.currentTimeMillis();
    StepVerifier.create(adapter.streamTicks()).expectNextCount(5).verifyComplete();
    var elapsed = System.currentTimeMillis() - start;

    assertThat(elapsed).isLessThan(500L);
  }

  @Test
  void speedMultiplierIntroducesDelayWithoutSkippingTicks() {
    var adapter = createAdapter(TEST_CSV, 1000.0);
    adapter.connect(List.of("AAPL"));

    StepVerifier.create(adapter.streamTicks()).expectNextCount(3).verifyComplete();
  }

  @Test
  void invalidCsvPathThrowsOnConnect() {
    var adapter = createAdapter("classpath:nonexistent.csv", 0.0);

    assertThatThrownBy(() -> adapter.connect(List.of("AAPL")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to load CSV");
  }

  @Test
  void malformedCsvRowThrowsOnConnect() {
    var adapter = createAdapter(MALFORMED_CSV, 0.0);

    assertThatThrownBy(() -> adapter.connect(List.of("AAPL")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected 10 fields");
  }

  @Test
  void historicalBarsThrowsUnsupported() {
    var adapter = createAdapter(TEST_CSV, 0.0);

    assertThatThrownBy(
            () ->
                adapter.getHistoricalBars(
                    "AAPL", LocalDate.now(), LocalDate.now(), BarTimeframe.ONE_DAY))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void isConnectedReturnsFalseBeforeConnect() {
    var adapter = createAdapter(TEST_CSV, 0.0);
    assertThat(adapter.isConnected()).isFalse();
  }

  @Test
  void isConnectedReturnsTrueAfterConnect() {
    var adapter = createAdapter(TEST_CSV, 0.0);
    adapter.connect(List.of("AAPL"));
    assertThat(adapter.isConnected()).isTrue();
  }

  @Test
  void isConnectedReturnsFalseAfterDisconnect() {
    var adapter = createAdapter(TEST_CSV, 0.0);
    adapter.connect(List.of("AAPL"));
    adapter.disconnect();
    assertThat(adapter.isConnected()).isFalse();
  }
}
