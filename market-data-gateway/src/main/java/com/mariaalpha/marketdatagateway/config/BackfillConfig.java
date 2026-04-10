package com.mariaalpha.marketdatagateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "market-data.backfill")
public record BackfillConfig(int lookbackDays) {
  public BackfillConfig {
    if (lookbackDays <= 0) {
      lookbackDays = 60;
    }
  }
}
