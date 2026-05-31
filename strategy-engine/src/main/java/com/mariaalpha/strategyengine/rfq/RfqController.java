package com.mariaalpha.strategyengine.rfq;

import com.mariaalpha.strategyengine.model.OrderSignal;
import com.mariaalpha.strategyengine.model.OrderType;
import com.mariaalpha.strategyengine.publisher.SignalPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * RFQ pricing endpoints (FR-40 / TDD §2.6.2).
 *
 * <ul>
 *   <li>{@code POST /api/rfq/quote} — returns a two-way quote built by {@link RfqPricingEngine}.
 *   <li>{@code POST /api/rfq/accept} — accepts a previously issued quote (by id), validates
 *       freshness and the requested side+price, and publishes an {@link OrderSignal} for the
 *       Execution Engine to pick up via the regular signals topic.
 *   <li>{@code GET /api/rfq/quotes/{id}} — debug endpoint, returns whatever the store remembers
 *       about a quote (active or expired).
 * </ul>
 */
@RestController
@RequestMapping("/api/rfq")
public class RfqController {

  private static final Logger LOG = LoggerFactory.getLogger(RfqController.class);
  private static final BigDecimal PRICE_TOLERANCE = new BigDecimal("0.01");

  private final RfqPricingEngine pricingEngine;
  private final RfqQuoteStore quoteStore;
  private final SignalPublisher signalPublisher;
  private final RfqMetrics metrics;
  private final RfqPricingConfig config;

  public RfqController(
      RfqPricingEngine pricingEngine,
      RfqQuoteStore quoteStore,
      SignalPublisher signalPublisher,
      RfqMetrics metrics,
      RfqPricingConfig config) {
    this.pricingEngine = pricingEngine;
    this.quoteStore = quoteStore;
    this.signalPublisher = signalPublisher;
    this.metrics = metrics;
    this.config = config;
  }

  /** Compute an inventory- and vol-aware RFQ quote (issues 2.4.1 / 2.4.2). */
  @PostMapping("/quote")
  public ResponseEntity<RfqQuoteResponse> quote(@RequestBody RfqQuoteRequest request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body required");
    }
    try {
      var quote = pricingEngine.quote(request.symbol(), request.quantity());
      quoteStore.put(quote);
      return ResponseEntity.ok(RfqQuoteResponse.from(quote, config.quoteValidityMs()));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
  }

  /** Accept a previously issued RFQ quote and publish an order signal. */
  @PostMapping("/accept")
  public ResponseEntity<RfqAcceptResponse> accept(@RequestBody RfqAcceptRequest request) {
    if (request == null
        || request.quoteId() == null
        || request.side() == null
        || request.price() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "quoteId, side and price are required");
    }
    var quote =
        quoteStore
            .lookupActive(request.quoteId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.GONE, "Quote expired or unknown"));

    BigDecimal expectedPrice =
        switch (request.side()) {
          case BUY -> quote.ask();
          case SELL -> quote.bid();
        };
    if (request.price().subtract(expectedPrice).abs().compareTo(PRICE_TOLERANCE) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          String.format(
              "Accepted price %s differs from quoted %s side %s",
              request.price(), expectedPrice, request.side()));
    }

    var signal =
        new OrderSignal(
            quote.symbol(),
            request.side(),
            quote.quantity(),
            OrderType.LIMIT,
            expectedPrice,
            "RFQ",
            Instant.now());
    signalPublisher.publish(signal);
    metrics.recordAccept(quote.symbol(), request.side().name());
    LOG.info(
        "RFQ accept: quote={} {} {}@{} symbol={} qty={}",
        quote.quoteId(),
        request.side(),
        expectedPrice,
        quote.quantity(),
        quote.symbol(),
        quote.quantity());
    return ResponseEntity.ok(
        new RfqAcceptResponse(quote.quoteId(), quote.symbol(), signal, "ACCEPTED"));
  }

  /** Look up a previously issued RFQ quote (debug). */
  @GetMapping("/quotes/{quoteId}")
  public ResponseEntity<RfqQuoteResponse> getQuote(@PathVariable java.util.UUID quoteId) {
    return quoteStore
        .peek(quoteId)
        .map(q -> RfqQuoteResponse.from(q, config.quoteValidityMs()))
        .map(ResponseEntity::ok)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quote not found"));
  }
}
