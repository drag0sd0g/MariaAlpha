package com.mariaalpha.marketdatagateway.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mariaalpha.marketdatagateway.adapter.MarketDataAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebSocketRunnerTest {

  @Test
  void runConnectsAdapterWithSymbols() {
    var adapter = mock(MarketDataAdapter.class);
    var runner = new WebSocketRunner(adapter, List.of("AAPL", "MSFT"));
    runner.run(null);
    verify(adapter).connect(List.of("AAPL", "MSFT"));
  }
}
