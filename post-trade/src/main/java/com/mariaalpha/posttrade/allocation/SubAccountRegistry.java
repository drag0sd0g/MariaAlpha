package com.mariaalpha.posttrade.allocation;

import com.mariaalpha.posttrade.allocation.SubAccountConfig.SubAccount;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SubAccountRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(SubAccountRegistry.class);

  private final SubAccountConfig config;
  private final List<SubAccount> orderedAccounts;
  private final AllocationMethod defaultMethod;

  public SubAccountRegistry(SubAccountConfig config) {
    this.config = config;
    var configured = config == null ? null : config.subAccounts();
    this.orderedAccounts = configured == null ? List.of() : List.copyOf(configured);
    this.defaultMethod =
        config != null && config.defaultMethod() != null
            ? config.defaultMethod()
            : AllocationMethod.PRO_RATA;
  }

  @PostConstruct
  void validate() {
    var seen = new java.util.HashSet<String>();
    for (var acct : orderedAccounts) {
      if (acct.name() == null || acct.name().isBlank()) {
        throw new IllegalStateException("Sub-account name must be non-blank");
      }
      if (acct.weight() <= 0) {
        throw new IllegalStateException(
            "Sub-account " + acct.name() + " has non-positive weight " + acct.weight());
      }
      if (!seen.add(acct.name())) {
        throw new IllegalStateException("Duplicate sub-account name: " + acct.name());
      }
    }
    LOG.info(
        "SubAccountRegistry initialized with {} accounts (default method = {}): {}",
        orderedAccounts.size(),
        defaultMethod,
        orderedAccounts.stream().map(SubAccount::name).toList());
  }

  public List<SubAccount> accounts() {
    return orderedAccounts;
  }

  public AllocationMethod defaultMethod() {
    return defaultMethod;
  }

  public boolean isConfigured() {
    return !orderedAccounts.isEmpty();
  }
}
