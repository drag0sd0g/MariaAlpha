package com.mariaalpha.strategyengine.rfq;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RfqQuoteStore {

  private final ConcurrentHashMap<UUID, RfqQuote> quotes = new ConcurrentHashMap<>();
  private final Clock clock;

  @Autowired
  public RfqQuoteStore() {
    this(Clock.systemUTC());
  }

  RfqQuoteStore(Clock clock) {
    this.clock = clock;
  }

  public void put(RfqQuote quote) {
    quotes.put(quote.quoteId(), quote);
  }

  public Optional<RfqQuote> lookupActive(UUID quoteId) {
    var q = quotes.get(quoteId);
    if (q == null) {
      return Optional.empty();
    }
    if (Instant.now(clock).isAfter(q.expiresAt())) {
      quotes.remove(quoteId);
      return Optional.empty();
    }
    return Optional.of(q);
  }

  public Optional<RfqQuote> peek(UUID quoteId) {
    return Optional.ofNullable(quotes.get(quoteId));
  }
}
