package com.mariaalpha.strategyengine.options;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "strategy-engine.options")
public record OptionsPricingConfig(
    double thetaDayCount,
    int impliedVolMaxIterations,
    double impliedVolTolerance,
    double impliedVolLowerBound,
    double impliedVolUpperBound) {

  public OptionsPricingConfig {
    if (thetaDayCount <= 0) {
      thetaDayCount = 365.0;
    }
    if (impliedVolMaxIterations <= 0) {
      impliedVolMaxIterations = 100;
    }
    if (impliedVolTolerance <= 0) {
      impliedVolTolerance = 1.0e-6;
    }
    if (impliedVolLowerBound <= 0) {
      impliedVolLowerBound = 1.0e-4;
    }
    if (impliedVolUpperBound <= impliedVolLowerBound) {
      impliedVolUpperBound = 5.0;
    }
  }
}
