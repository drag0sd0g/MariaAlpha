package com.mariaalpha.posttrade.allocation;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "post-trade.allocation")
public record SubAccountConfig(AllocationMethod defaultMethod, List<SubAccount> subAccounts) {

  public record SubAccount(String name, double weight) {}
}
