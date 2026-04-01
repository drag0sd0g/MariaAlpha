package com.mariaalpha.marketdatagateway.adapter.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.marketdatagateway.config.AlpacaMarketDataConfig;
import com.mariaalpha.marketdatagateway.model.DataSource;
import com.mariaalpha.marketdatagateway.model.MarketTick;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class AlpacaMarketDataAdapterIntegrationTest {

  private AlpacaMarketDataAdapter adapter;

  @AfterEach
  void tearDown() {
    if (adapter != null) {
      adapter.disconnect();
    }
  }

  @Test
  void connectsAndReceivesTicks() throws InterruptedException {
    var config =
        new AlpacaMarketDataConfig(
            "wss://stream.data.alpaca.markets/v2/iex",
            System.getenv("ALPACA_API_KEY_ID"),
            System.getenv("ALPACA_API_SECRET_KEY"));
    adapter = new AlpacaMarketDataAdapter(config);
    adapter.connect(List.of("AAPL", "MSFT"));

    var receivedTicks = new ArrayList<MarketTick>();
    var latch = new CountDownLatch(1);

    adapter
        .streamTicks()
        .doOnNext(
            tick -> {
              receivedTicks.add(tick);
              latch.countDown();
            })
        .subscribe();

    var received = latch.await(10, TimeUnit.SECONDS);

    if (received) {
      var tick = receivedTicks.getFirst();
      assertThat(tick.source()).isEqualTo(DataSource.ALPACA);
      assertThat(tick.symbol()).isIn("AAPL", "MSFT");
      assertThat(tick.timestamp()).isNotNull();
    }
  }
}
