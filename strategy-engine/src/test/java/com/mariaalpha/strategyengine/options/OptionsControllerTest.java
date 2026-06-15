package com.mariaalpha.strategyengine.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OptionsControllerTest {

  private static final OptionsPricingConfig CONFIG =
      new OptionsPricingConfig(365.0, 100, 1.0e-6, 1.0e-4, 5.0);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final OptionsMetrics metrics = new OptionsMetrics(registry);
  private final BlackScholesPricer pricer = new BlackScholesPricer();
  private final GreeksCalculator greeks = new GreeksCalculator(CONFIG);
  private final OptionPricingService pricingService =
      new OptionPricingService(pricer, greeks, metrics);
  private final ImpliedVolatilityCalculator ivCalc =
      new ImpliedVolatilityCalculator(pricer, CONFIG);
  private final OptionsController controller =
      new OptionsController(pricingService, ivCalc, metrics);

  @Test
  void priceEndpointReturnsTextbookValues() {
    var request =
        new OptionPricingRequest("AAPL", 42.0, 40.0, 0.5, 0.20, 0.10, 0.0, OptionType.CALL);
    var response = controller.price(request).getBody();
    assertThat(response).isNotNull();
    assertThat(response.symbol()).isEqualTo("AAPL");
    assertThat(response.type()).isEqualTo(OptionType.CALL);
    assertThat(response.price()).isCloseTo(4.76, org.assertj.core.api.Assertions.within(0.01));
    assertThat(response.greeks().delta())
        .isCloseTo(0.7791, org.assertj.core.api.Assertions.within(0.001));
  }

  @Test
  void greeksEndpointReturnsGreeksOnly() {
    var request = new OptionPricingRequest("MSFT", 100, 100, 0.5, 0.25, 0.03, 0.01, OptionType.PUT);
    var response = controller.greeks(request).getBody();
    assertThat(response).isNotNull();
    assertThat(response.symbol()).isEqualTo("MSFT");
    assertThat(response.greeks().delta()).isLessThan(0.0);
  }

  @Test
  void priceEndpointRejectsBadInputsAs400() {
    var request =
        new OptionPricingRequest("AAPL", -100.0, 40.0, 0.5, 0.20, 0.10, 0.0, OptionType.CALL);
    assertThatThrownBy(() -> controller.price(request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
  }

  @Test
  void impliedVolEndpointRoundTrips() {
    var pricingReq =
        new OptionPricingRequest("AAPL", 100, 100, 0.5, 0.35, 0.04, 0.0, OptionType.CALL);
    var pricing = controller.price(pricingReq).getBody();
    assertThat(pricing).isNotNull();
    double premium = pricing.price();

    var ivReq =
        new ImpliedVolatilityRequest("AAPL", 100, 100, 0.5, 0.04, 0.0, OptionType.CALL, premium);
    var iv = controller.impliedVolatility(ivReq).getBody();
    assertThat(iv).isNotNull();
    assertThat(iv.impliedVolatility())
        .isCloseTo(0.35, org.assertj.core.api.Assertions.within(1e-4));
    assertThat(iv.method()).isNotNull();
  }

  @Test
  void impliedVolEndpointRejectsImpossiblePremiumAs422() {
    var request =
        new ImpliedVolatilityRequest("AAPL", 100, 100, 1.0, 0.05, 0.0, OptionType.CALL, 105.0);
    assertThatThrownBy(() -> controller.impliedVolatility(request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
  }
}
