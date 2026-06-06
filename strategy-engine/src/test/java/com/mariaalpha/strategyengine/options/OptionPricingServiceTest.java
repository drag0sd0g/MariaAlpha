package com.mariaalpha.strategyengine.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class OptionPricingServiceTest {

  private static final OptionsPricingConfig CONFIG =
      new OptionsPricingConfig(365.0, 100, 1.0e-6, 1.0e-4, 5.0);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final OptionsMetrics metrics = new OptionsMetrics(registry);
  private final OptionPricingService service =
      new OptionPricingService(new BlackScholesPricer(), new GreeksCalculator(CONFIG), metrics);

  @Test
  void priceAndGreeksAgreeWithIndividualComponents() {
    var contract = new OptionContract(42.0, 40.0, 0.5, 0.20, 0.10, 0.0, OptionType.CALL);
    var priced = service.priceWithGreeks(contract);
    assertThat(priced.price()).isCloseTo(4.76, within(0.01));
    assertThat(priced.greeks().delta()).isCloseTo(0.7791, within(0.001));
  }

  @Test
  void priceRecordsCounterAndTimer() {
    var contract = new OptionContract(100, 100, 0.5, 0.25, 0.03, 0.0, OptionType.CALL);
    service.price(contract);
    service.price(contract);
    double count =
        registry.find("mariaalpha_options_pricings_total").tag("type", "CALL").counter().count();
    assertThat(count).isEqualTo(2.0);
    var timer = registry.find("mariaalpha_options_pricing_duration").tag("type", "CALL").timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(2);
  }
}
