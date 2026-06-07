package com.mariaalpha.posttrade.controller.dto;

import com.mariaalpha.posttrade.allocation.AllocationMethod;
import com.mariaalpha.posttrade.allocation.SubAccountConfig.SubAccount;
import com.mariaalpha.posttrade.allocation.SubAccountRegistry;
import java.util.List;

/** Roster of configured sub-accounts plus the registry-level default method. */
public record SubAccountResponse(
    AllocationMethod defaultMethod, List<SubAccountRow> subAccounts) {

  public record SubAccountRow(String name, double weight) {}

  public static SubAccountResponse from(SubAccountRegistry registry) {
    var rows =
        registry.accounts().stream()
            .map((SubAccount s) -> new SubAccountRow(s.name(), s.weight()))
            .toList();
    return new SubAccountResponse(registry.defaultMethod(), rows);
  }
}
