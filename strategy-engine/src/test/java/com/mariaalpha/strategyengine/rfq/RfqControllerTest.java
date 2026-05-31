package com.mariaalpha.strategyengine.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.Side;
import com.mariaalpha.strategyengine.publisher.SignalPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RfqControllerTest {

  private RfqPricingEngine engine;
  private RfqQuoteStore store;
  private SignalPublisher publisher;
  private RfqMetrics metrics;
  private RfqPricingConfig config;
  private MockMvc mvc;
  private ObjectMapper json;

  @BeforeEach
  void setUp() {
    engine = org.mockito.Mockito.mock(RfqPricingEngine.class);
    publisher = org.mockito.Mockito.mock(SignalPublisher.class);
    metrics = new RfqMetrics(new SimpleMeterRegistry());
    config =
        new RfqPricingConfig(4.0, 1.0, 1_000_000.0, 30.0, 0.5, 0.3, 10_000L, "http://x", 500L, 30);
    store = new RfqQuoteStore();
    var controller = new RfqController(engine, store, publisher, metrics, config);
    mvc = MockMvcBuilders.standaloneSetup(controller).build();
    json = new ObjectMapper().findAndRegisterModules();
  }

  @Test
  void postQuoteReturns200WithBreakdown() throws Exception {
    var quote = sampleQuote("AAPL", 100);
    when(engine.quote("AAPL", 100)).thenReturn(quote);

    mvc.perform(
            post("/api/rfq/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new RfqQuoteRequest("AAPL", 100))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value("AAPL"))
        .andExpect(jsonPath("$.quantity").value(100))
        .andExpect(jsonPath("$.bid").value(100.08))
        .andExpect(jsonPath("$.ask").value(100.12))
        .andExpect(jsonPath("$.breakdown.totalHalfSpreadBps").value(2.0))
        .andExpect(jsonPath("$.validForMs").value(10_000));
  }

  @Test
  void postQuoteWithoutMarketDataReturns503() throws Exception {
    when(engine.quote("XYZ", 100)).thenThrow(new IllegalStateException("No market data for XYZ"));
    mvc.perform(
            post("/api/rfq/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new RfqQuoteRequest("XYZ", 100))))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void postQuoteWithBadInputReturns400() throws Exception {
    when(engine.quote("AAPL", -1)).thenThrow(new IllegalArgumentException("quantity must be > 0"));
    mvc.perform(
            post("/api/rfq/quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new RfqQuoteRequest("AAPL", -1))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void acceptUnknownQuoteReturns410() throws Exception {
    mvc.perform(
            post("/api/rfq/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new RfqAcceptRequest(
                            UUID.randomUUID(), Side.BUY, new BigDecimal("100.12")))))
        .andExpect(status().isGone());
    verify(publisher, never()).publish(any());
  }

  @Test
  void acceptMatchingBuyAtAskPublishesOrderSignal() throws Exception {
    var quote = sampleQuote("AAPL", 100);
    store.put(quote);
    mvc.perform(
            post("/api/rfq/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new RfqAcceptRequest(quote.quoteId(), Side.BUY, quote.ask()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.signal.side").value("BUY"));

    var captor = ArgumentCaptor.forClass(OrderSignal.class);
    verify(publisher).publish(captor.capture());
    assertThat(captor.getValue().symbol()).isEqualTo("AAPL");
    assertThat(captor.getValue().side()).isEqualTo(Side.BUY);
    assertThat(captor.getValue().quantity()).isEqualTo(100);
    assertThat(captor.getValue().limitPrice()).isEqualByComparingTo(quote.ask());
    assertThat(captor.getValue().strategyName()).isEqualTo("RFQ");
  }

  @Test
  void acceptMatchingSellAtBidPublishesOrderSignal() throws Exception {
    var quote = sampleQuote("AAPL", 200);
    store.put(quote);
    mvc.perform(
            post("/api/rfq/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new RfqAcceptRequest(quote.quoteId(), Side.SELL, quote.bid()))))
        .andExpect(status().isOk());
    var captor = ArgumentCaptor.forClass(OrderSignal.class);
    verify(publisher).publish(captor.capture());
    assertThat(captor.getValue().side()).isEqualTo(Side.SELL);
    assertThat(captor.getValue().limitPrice()).isEqualByComparingTo(quote.bid());
  }

  @Test
  void acceptWithStalePriceReturns409() throws Exception {
    var quote = sampleQuote("AAPL", 100);
    store.put(quote);
    mvc.perform(
            post("/api/rfq/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new RfqAcceptRequest(quote.quoteId(), Side.BUY, new BigDecimal("99.00")))))
        .andExpect(status().isConflict());
    verify(publisher, never()).publish(any());
  }

  @Test
  void acceptMissingFieldsReturns400() throws Exception {
    mvc.perform(post("/api/rfq/accept").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  private static RfqQuote sampleQuote(String symbol, int qty) {
    Instant now = Instant.now();
    return new RfqQuote(
        UUID.randomUUID(),
        symbol,
        qty,
        new BigDecimal("100.10"),
        new BigDecimal("100.10"),
        new BigDecimal("100.08"),
        new BigDecimal("100.12"),
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        2.0,
        2.0,
        60_000_000L,
        now,
        now.plusMillis(10_000));
  }
}
