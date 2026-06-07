package com.mariaalpha.posttrade.allocation;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sub-account roster used by the allocation engine (roadmap 3.4.2).
 *
 * <p>For the MVP, sub-accounts are statically configured under {@code post-trade.allocation}.
 * Production deployments would back this with a desk-management service or vendor data; the
 * abstraction is intentionally shallow so adding a richer source later means swapping the
 * {@link SubAccountRegistry} bean.
 *
 * <p>The {@code defaultMethod} applies when an allocation request doesn't specify one. Per-account
 * {@code weight} is unitless: pro-rata divides by the sum of weights, FIFO interprets weight as a
 * share cap.
 */
@ConfigurationProperties(prefix = "post-trade.allocation")
public record SubAccountConfig(AllocationMethod defaultMethod, List<SubAccount> subAccounts) {

  public record SubAccount(String name, double weight) {}
}
