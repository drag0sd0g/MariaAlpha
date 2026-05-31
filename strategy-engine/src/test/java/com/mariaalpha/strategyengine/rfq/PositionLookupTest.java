package com.mariaalpha.strategyengine.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import org.junit.jupiter.api.Test;

class PositionLookupTest {

  private static final RfqPricingConfig CONFIG =
      new RfqPricingConfig(
          4.0, 1.0, 1_000_000.0, 30.0, 0.5, 0.3, 10_000L, "http://localhost:8086", 500L, 30);

  @SuppressWarnings("unchecked")
  @Test
  void parsesSuccessfulResponse() throws Exception {
    var http = mock(HttpClient.class);
    var resp = mock(HttpResponse.class);
    when(resp.statusCode()).thenReturn(200);
    when(resp.body())
        .thenReturn("{\"symbol\":\"AAPL\",\"netQuantity\":250,\"lastMarkPrice\":178.5}");
    when(http.send(any(), any(BodyHandler.class))).thenReturn(resp);

    var lookup = new PositionLookup(http, new ObjectMapper(), CONFIG);
    var pos = lookup.fetch("AAPL");
    assertThat(pos.available()).isTrue();
    assertThat(pos.netQuantity()).isEqualByComparingTo("250");
    assertThat(pos.lastMarkPrice()).isEqualByComparingTo("178.5");
  }

  @SuppressWarnings("unchecked")
  @Test
  void notFoundTreatedAsFlat() throws Exception {
    var http = mock(HttpClient.class);
    var resp = mock(HttpResponse.class);
    when(resp.statusCode()).thenReturn(404);
    when(http.send(any(), any(BodyHandler.class))).thenReturn(resp);

    var lookup = new PositionLookup(http, new ObjectMapper(), CONFIG);
    var pos = lookup.fetch("AAPL");
    assertThat(pos.available()).isTrue();
    assertThat(pos.netQuantity()).isEqualByComparingTo("0");
  }

  @SuppressWarnings("unchecked")
  @Test
  void ioExceptionFallsBackToUnavailable() throws Exception {
    var http = mock(HttpClient.class);
    when(http.send(any(), any(BodyHandler.class))).thenThrow(new IOException("boom"));
    var lookup = new PositionLookup(http, new ObjectMapper(), CONFIG);
    var pos = lookup.fetch("AAPL");
    assertThat(pos.available()).isFalse();
    assertThat(pos.netQuantity()).isEqualByComparingTo("0");
  }

  @SuppressWarnings("unchecked")
  @Test
  void unexpectedStatusFallsBackToUnavailable() throws Exception {
    var http = mock(HttpClient.class);
    var resp = mock(HttpResponse.class);
    when(resp.statusCode()).thenReturn(500);
    when(resp.body()).thenReturn("");
    when(http.send(any(), any(BodyHandler.class))).thenReturn(resp);
    var lookup = new PositionLookup(http, new ObjectMapper(), CONFIG);
    var pos = lookup.fetch("AAPL");
    assertThat(pos.available()).isFalse();
  }
}
