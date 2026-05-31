package com.mariaalpha.strategyengine.rfq;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RfqQuoteStoreTest {

  @Test
  void activeWhenWithinValidity() {
    var clock = Clock.fixed(Instant.parse("2026-03-24T14:00:00Z"), ZoneOffset.UTC);
    var store = new RfqQuoteStore(clock);
    var quote = sample("AAPL", Instant.parse("2026-03-24T14:00:05Z"));
    store.put(quote);
    assertThat(store.lookupActive(quote.quoteId())).contains(quote);
  }

  @Test
  void expiredQuoteEvictedAndReturnedEmpty() {
    var t0 = Instant.parse("2026-03-24T14:00:00Z");
    var clock = Clock.fixed(t0, ZoneOffset.UTC);
    var store = new RfqQuoteStore(clock);
    var quote = sample("AAPL", t0.minusSeconds(1));
    store.put(quote);
    assertThat(store.lookupActive(quote.quoteId())).isEmpty();
    assertThat(store.peek(quote.quoteId())).isEmpty();
  }

  @Test
  void peekReturnsUnexpiredEntry() {
    var clock = Clock.fixed(Instant.parse("2026-03-24T14:00:00Z"), ZoneOffset.UTC);
    var store = new RfqQuoteStore(clock);
    var quote = sample("AAPL", Instant.parse("2026-03-24T14:00:05Z"));
    store.put(quote);
    assertThat(store.peek(quote.quoteId())).contains(quote);
  }

  @Test
  void lookupActiveReturnsEmptyForUnknownId() {
    var store = new RfqQuoteStore();
    assertThat(store.lookupActive(UUID.randomUUID())).isEmpty();
  }

  private static RfqQuote sample(String symbol, Instant expiresAt) {
    return new RfqQuote(
        UUID.randomUUID(),
        symbol,
        100,
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
        expiresAt.minusSeconds(10),
        expiresAt);
  }
}
