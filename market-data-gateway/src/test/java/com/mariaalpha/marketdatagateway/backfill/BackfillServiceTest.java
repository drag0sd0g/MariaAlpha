package com.mariaalpha.marketdatagateway.backfill;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.RateLimiter;
import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import com.mariaalpha.marketdatagateway.config.BackfillConfig;
import com.mariaalpha.marketdatagateway.model.BarTimeframe;
import com.mariaalpha.marketdatagateway.model.HistoricalBar;
import com.mariaalpha.marketdatagateway.repository.HistoricalBarRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BackfillServiceTest {

  private MarketDataAdapter adapter;
  private HistoricalBarRepository repository;
  private BackfillService service;

  @BeforeEach
  void setUp() {
    adapter = mock(MarketDataAdapter.class);
    repository = mock(HistoricalBarRepository.class);
    var config = new BackfillConfig(60);
    service =
        new BackfillService(adapter, repository, config, RateLimiter.create(Double.MAX_VALUE));
  }

  @Test
  void backfillFetchesAndPersistsForEachSymbol() {
    when(adapter.getHistoricalBars(any(), any(), any(), any())).thenReturn(List.of(sampleBar()));

    service.backfill(List.of("AAPL", "MSFT", "GOOGL"));

    verify(adapter).getHistoricalBars(eq("AAPL"), any(), any(), any());
    verify(adapter).getHistoricalBars(eq("MSFT"), any(), any(), any());
    verify(adapter).getHistoricalBars(eq("GOOGL"), any(), any(), any());
    verify(repository, org.mockito.Mockito.times(3)).upsertAll(any());
  }

  @Test
  void backfillUsesConfiguredDateRange() {
    when(adapter.getHistoricalBars(any(), any(), any(), any())).thenReturn(List.of());

    service.backfill(List.of("AAPL"));

    var fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
    var toCaptor = ArgumentCaptor.forClass(LocalDate.class);
    verify(adapter).getHistoricalBars(eq("AAPL"), fromCaptor.capture(), toCaptor.capture(), any());

    var today = LocalDate.now();
    org.assertj.core.api.Assertions.assertThat(toCaptor.getValue()).isEqualTo(today);
    org.assertj.core.api.Assertions.assertThat(fromCaptor.getValue())
        .isEqualTo(today.minusDays(60));
  }

  @Test
  void backfillPassesOneDayTimeframe() {
    when(adapter.getHistoricalBars(any(), any(), any(), any())).thenReturn(List.of());

    service.backfill(List.of("AAPL"));

    verify(adapter).getHistoricalBars(any(), any(), any(), eq(BarTimeframe.ONE_DAY));
  }

  @Test
  void backfillContinuesOnSingleSymbolFailure() {
    when(adapter.getHistoricalBars(eq("AAPL"), any(), any(), any()))
        .thenThrow(new RuntimeException("API error"));
    when(adapter.getHistoricalBars(eq("MSFT"), any(), any(), any()))
        .thenReturn(List.of(sampleBar()));

    service.backfill(List.of("AAPL", "MSFT"));

    verify(adapter).getHistoricalBars(eq("MSFT"), any(), any(), any());
    verify(repository).upsertAll(any());
  }

  @Test
  void backfillDoesNotPersistWhenNoBarsReturned() {
    when(adapter.getHistoricalBars(any(), any(), any(), any())).thenReturn(List.of());

    service.backfill(List.of("AAPL"));

    verify(repository).upsertAll(List.of());
  }

  private static HistoricalBar sampleBar() {
    return new HistoricalBar(
        "AAPL",
        LocalDate.of(2026, 1, 2),
        new BigDecimal("185.59"),
        new BigDecimal("186.10"),
        new BigDecimal("184.27"),
        new BigDecimal("185.64"),
        45234567L,
        new BigDecimal("185.42"));
  }
}
