package com.mariaalpha.posttrade.allocation;

/**
 * Algorithm used to split a parent fill across sub-accounts.
 *
 * <ul>
 *   <li>{@link #PRO_RATA} — quantity allocated to each sub-account is proportional to its
 *       configured weight (weight ÷ Σ weights × parent_quantity). Rounded down to a whole number of
 *       shares; the unallocated remainder is awarded to the highest-weighted sub-account so the sum
 *       of allocations equals the parent fill exactly. This is the industry-standard default.
 *   <li>{@link #FIFO} — sub-accounts are filled in declaration order until the parent quantity is
 *       exhausted. Each sub-account is awarded at most its weight (interpreted as a share cap) of
 *       the parent. Useful for waterfall arrangements where the first sub-account has priority.
 * </ul>
 */
public enum AllocationMethod {
  PRO_RATA,
  FIFO
}
