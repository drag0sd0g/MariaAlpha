package com.mariaalpha.posttrade.allocation;

import com.mariaalpha.posttrade.allocation.SubAccountConfig.SubAccount;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AllocationCalculator {

  public List<AllocationResult> allocate(
      List<SubAccount> subAccounts,
      AllocationMethod method,
      BigDecimal filledQuantity,
      BigDecimal avgPrice) {
    if (subAccounts == null || subAccounts.isEmpty()) {
      return List.of();
    }
    if (filledQuantity == null || filledQuantity.signum() <= 0) {
      return List.of();
    }
    if (avgPrice == null || avgPrice.signum() < 0) {
      throw new IllegalArgumentException("avgPrice must be ≥ 0 (was " + avgPrice + ")");
    }
    return switch (method) {
      case PRO_RATA -> proRata(subAccounts, filledQuantity, avgPrice);
      case FIFO -> fifo(subAccounts, filledQuantity, avgPrice);
    };
  }

  private List<AllocationResult> proRata(
      List<SubAccount> subAccounts, BigDecimal filledQuantity, BigDecimal avgPrice) {
    double totalWeight = subAccounts.stream().mapToDouble(SubAccount::weight).sum();
    if (totalWeight <= 0) {
      return List.of();
    }
    var results = new ArrayList<AllocationResult>(subAccounts.size());
    BigDecimal allocatedSoFar = BigDecimal.ZERO;
    for (var acct : subAccounts) {
      if (acct.weight() <= 0) {
        continue;
      }
      BigDecimal share =
          filledQuantity
              .multiply(BigDecimal.valueOf(acct.weight()))
              .divide(BigDecimal.valueOf(totalWeight), 0, RoundingMode.FLOOR);
      results.add(new AllocationResult(acct.name(), share, avgPrice, AllocationMethod.PRO_RATA));
      allocatedSoFar = allocatedSoFar.add(share);
    }
    var remainder = filledQuantity.subtract(allocatedSoFar);
    if (remainder.signum() > 0 && !results.isEmpty()) {
      var topIndex = findHeaviestAccountIndex(subAccounts, results);
      var current = results.get(topIndex);
      results.set(
          topIndex,
          new AllocationResult(
              current.subAccount(),
              current.allocatedQuantity().add(remainder),
              avgPrice,
              AllocationMethod.PRO_RATA));
    }
    return List.copyOf(results);
  }

  private int findHeaviestAccountIndex(
      List<SubAccount> subAccounts, List<AllocationResult> results) {
    int bestIndex = 0;
    double bestWeight = -1.0;
    for (int i = 0; i < results.size(); i++) {
      var name = results.get(i).subAccount();
      var weight =
          subAccounts.stream()
              .filter(s -> s.name().equals(name))
              .map(SubAccount::weight)
              .findFirst()
              .orElse(0.0);
      if (weight > bestWeight) {
        bestWeight = weight;
        bestIndex = i;
      }
    }
    return bestIndex;
  }

  private List<AllocationResult> fifo(
      List<SubAccount> subAccounts, BigDecimal filledQuantity, BigDecimal avgPrice) {
    var results = new ArrayList<AllocationResult>(subAccounts.size());
    BigDecimal remaining = filledQuantity;
    for (var acct : subAccounts) {
      if (remaining.signum() <= 0) {
        break;
      }
      var cap = BigDecimal.valueOf(acct.weight()).setScale(0, RoundingMode.FLOOR);
      if (cap.signum() <= 0) {
        continue;
      }
      var share = cap.min(remaining);
      results.add(new AllocationResult(acct.name(), share, avgPrice, AllocationMethod.FIFO));
      remaining = remaining.subtract(share);
    }
    if (remaining.signum() > 0 && !results.isEmpty()) {
      var lastIdx = results.size() - 1;
      var last = results.get(lastIdx);
      results.set(
          lastIdx,
          new AllocationResult(
              last.subAccount(),
              last.allocatedQuantity().add(remaining),
              avgPrice,
              AllocationMethod.FIFO));
    }
    return List.copyOf(results);
  }

  static double totalWeight(List<SubAccount> subAccounts) {
    return subAccounts.stream().filter(s -> s.weight() > 0).mapToDouble(SubAccount::weight).sum();
  }

  static Comparator<SubAccount> byWeightDescending() {
    return Comparator.comparingDouble(SubAccount::weight).reversed();
  }
}
