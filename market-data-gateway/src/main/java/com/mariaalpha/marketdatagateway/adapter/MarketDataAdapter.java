package com.mariaalpha.marketdatagateway.adapter;

import com.mariaalpha.marketdatagateway.model.BarTimeframe;
import com.mariaalpha.marketdatagateway.model.HistoricalBar;
import com.mariaalpha.marketdatagateway.model.MarketTick;
import java.time.LocalDate;
import java.util.List;
import reactor.core.publisher.Flux;

public interface MarketDataAdapter {
  void connect(List<String> symbols);

  void disconnect();

  Flux<MarketTick> streamTicks();

  List<HistoricalBar> getHistoricalBars(
      String symbol, LocalDate from, LocalDate to, BarTimeframe timeframe);

  boolean isConnected();
}
