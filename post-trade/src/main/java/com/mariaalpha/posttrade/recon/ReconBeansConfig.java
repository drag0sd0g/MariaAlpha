package com.mariaalpha.posttrade.recon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
