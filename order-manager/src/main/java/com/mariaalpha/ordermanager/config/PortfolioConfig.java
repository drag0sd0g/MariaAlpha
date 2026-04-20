package com.mariaalpha.ordermanager.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order-manager.portfolio")
public record PortfolioConfig(BigDecimal initialCash) {}
