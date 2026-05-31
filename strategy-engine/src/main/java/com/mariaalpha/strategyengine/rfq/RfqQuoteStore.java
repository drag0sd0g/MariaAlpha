package com.mariaalpha.strategyengine.rfq;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Short-lived in-memory store of issued RFQ quotes so that the accept endpoint can validate quoteId
 * + freshness. Quotes are evicted on a best-effort basis when looked up after expiry — a dedicated
 * reaper is over-engineered for the volume this endpoint sees.
 */
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

  /** Returns the quote if present and not yet expired; evicts expired entries on the way out. */
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
