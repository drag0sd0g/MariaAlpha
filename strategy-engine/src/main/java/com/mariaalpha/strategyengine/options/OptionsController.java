package com.mariaalpha.strategyengine.options;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/options")
public class OptionsController {

  private final OptionPricingService pricingService;
  private final ImpliedVolatilityCalculator impliedVolatilityCalculator;
  private final OptionsMetrics metrics;

  public OptionsController(
      OptionPricingService pricingService,
      ImpliedVolatilityCalculator impliedVolatilityCalculator,
      OptionsMetrics metrics) {
    this.pricingService = pricingService;
    this.impliedVolatilityCalculator = impliedVolatilityCalculator;
    this.metrics = metrics;
  }

  @PostMapping("/price")
  public ResponseEntity<OptionPricingResponse> price(@RequestBody OptionPricingRequest request) {
    requireBody(request);
    try {
      var contract = request.toContract();
      var priced = pricingService.priceWithGreeks(contract);
      return ResponseEntity.ok(OptionPricingResponse.of(request, priced.price(), priced.greeks()));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PostMapping("/greeks")
  public ResponseEntity<GreeksResponse> greeks(@RequestBody OptionPricingRequest request) {
    requireBody(request);
    try {
      var greeks = pricingService.greeks(request.toContract());
      return ResponseEntity.ok(GreeksResponse.of(request, greeks));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PostMapping("/implied-volatility")
  public ResponseEntity<ImpliedVolatilityResponse> impliedVolatility(
      @RequestBody ImpliedVolatilityRequest request) {
    requireBody(request);
    try {
      var result =
          impliedVolatilityCalculator.solve(
              request.spot(),
              request.strike(),
              request.timeToExpiryYears(),
              request.riskFreeRate(),
              request.dividendYield(),
              request.type(),
              request.marketPrice());
      metrics.recordImpliedVolSolve(request.type(), result.method(), result.iterations());
      return ResponseEntity.ok(ImpliedVolatilityResponse.of(request, result));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
  }

  private static void requireBody(Object body) {
    if (body == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body required");
    }
  }
}
