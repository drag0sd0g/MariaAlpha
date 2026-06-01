package com.mariaalpha.posttrade.recon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the reconciliation engine. The {@link AlpacaActivitiesClient} bean is selected
 * at startup based on {@code post-trade.recon.mode}; the rest of the engine is mode-agnostic.
 */
@Configuration
public class ReconBeansConfig {

  @Bean
  public AlpacaActivitiesClient alpacaActivitiesClient(
      ReconConfig reconConfig, ObjectMapper objectMapper) {
    return switch (reconConfig.mode()) {
      case EXTERNAL -> new HttpAlpacaActivitiesClient(reconConfig.alpaca(), objectMapper);
      case MIRROR -> new MirroredAlpacaActivitiesClient();
    };
  }

  @Bean
  public ReconciliationComparator reconciliationComparator(ReconConfig config) {
    return new ReconciliationComparator(
        config.priceToleranceBps(),
        config.quantityTolerance(),
        config.highSeverityNotional(),
        config.criticalSeverityNotional());
  }
}
