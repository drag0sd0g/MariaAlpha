package com.mariaalpha.posttrade.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mariaalpha.posttrade.allocation.SubAccountConfig.SubAccount;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AllocationCalculatorTest {

  private final AllocationCalculator calculator = new AllocationCalculator();

  private static final List<SubAccount> ACCOUNTS =
      List.of(
          new SubAccount("HOUSE", 50.0),
          new SubAccount("HF_A", 30.0),
          new SubAccount("HF_B", 20.0));

  private static final BigDecimal AVG_PRICE = new BigDecimal("178.42");

  @Test
  void proRataNoRemainderSplitsCleanly() {
    var results =
        calculator.allocate(ACCOUNTS, AllocationMethod.PRO_RATA, new BigDecimal("1000"), AVG_PRICE);
    assertThat(results).hasSize(3);
    assertThat(results.get(0).subAccount()).isEqualTo("HOUSE");
    assertThat(results.get(0).allocatedQuantity()).isEqualByComparingTo("500");
    assertThat(results.get(1).subAccount()).isEqualTo("HF_A");
    assertThat(results.get(1).allocatedQuantity()).isEqualByComparingTo("300");
    assertThat(results.get(2).subAccount()).isEqualTo("HF_B");
    assertThat(results.get(2).allocatedQuantity()).isEqualByComparingTo("200");
    assertThat(results)
        .allSatisfy(r -> assertThat(r.allocatedAvgPrice()).isEqualByComparingTo(AVG_PRICE))
        .allSatisfy(r -> assertThat(r.method()).isEqualTo(AllocationMethod.PRO_RATA));
  }

  @Test
  void proRataRemainderGoesToHeaviestAccount() {
    var results =
        calculator.allocate(ACCOUNTS, AllocationMethod.PRO_RATA, new BigDecimal("1003"), AVG_PRICE);
    assertThat(results.get(0).allocatedQuantity()).isEqualByComparingTo("503");
    assertThat(results.get(1).allocatedQuantity()).isEqualByComparingTo("300");
    assertThat(results.get(2).allocatedQuantity()).isEqualByComparingTo("200");
    assertSumEquals(results, "1003");
  }

  @Test
  void proRataSingleAccount() {
    var accounts = List.of(new SubAccount("ONLY", 100.0));
    var results =
        calculator.allocate(accounts, AllocationMethod.PRO_RATA, new BigDecimal("500"), AVG_PRICE);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).allocatedQuantity()).isEqualByComparingTo("500");
  }

  @Test
  void proRataSkipsZeroWeightAccounts() {
    var accounts =
        List.of(
            new SubAccount("HOUSE", 50.0),
            new SubAccount("INACTIVE", 0.0),
            new SubAccount("HF_A", 50.0));
    var results =
        calculator.allocate(accounts, AllocationMethod.PRO_RATA, new BigDecimal("1000"), AVG_PRICE);
    assertThat(results).hasSize(2);
    assertThat(results).extracting(AllocationResult::subAccount).doesNotContain("INACTIVE");
    assertSumEquals(results, "1000");
  }

  @Test
  void fifoFillsInDeclarationOrderUntilCapsReached() {
    var results =
        calculator.allocate(ACCOUNTS, AllocationMethod.FIFO, new BigDecimal("600"), AVG_PRICE);
    assertThat(results).hasSize(3);
    assertThat(results.get(0).subAccount()).isEqualTo("HOUSE");
    assertThat(results.get(0).allocatedQuantity()).isEqualByComparingTo("50");
    assertThat(results.get(1).subAccount()).isEqualTo("HF_A");
    assertThat(results.get(1).allocatedQuantity()).isEqualByComparingTo("30");
    assertThat(results.get(2).subAccount()).isEqualTo("HF_B");
    assertThat(results.get(2).allocatedQuantity()).isEqualByComparingTo("520");
    assertSumEquals(results, "600");
  }

  @Test
  void fifoStopsEarlyWhenParentExhausted() {
    var results =
        calculator.allocate(ACCOUNTS, AllocationMethod.FIFO, new BigDecimal("25"), AVG_PRICE);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).subAccount()).isEqualTo("HOUSE");
    assertThat(results.get(0).allocatedQuantity()).isEqualByComparingTo("25");
  }

  @Test
  void fifoSpansMultipleAccountsForMediumParent() {
    var results =
        calculator.allocate(ACCOUNTS, AllocationMethod.FIFO, new BigDecimal("75"), AVG_PRICE);
    assertThat(results).hasSize(2);
    assertThat(results.get(0).allocatedQuantity()).isEqualByComparingTo("50");
    assertThat(results.get(1).allocatedQuantity()).isEqualByComparingTo("25");
    assertSumEquals(results, "75");
  }

  @Test
  void emptySubAccountListReturnsEmpty() {
    var results =
        calculator.allocate(List.of(), AllocationMethod.PRO_RATA, new BigDecimal("100"), AVG_PRICE);
    assertThat(results).isEmpty();
  }

  @Test
  void zeroParentQuantityReturnsEmpty() {
    var results =
        calculator.allocate(ACCOUNTS, AllocationMethod.PRO_RATA, BigDecimal.ZERO, AVG_PRICE);
    assertThat(results).isEmpty();
  }

  @Test
  void rejectsNegativePrice() {
    assertThatThrownBy(
            () ->
                calculator.allocate(
                    ACCOUNTS,
                    AllocationMethod.PRO_RATA,
                    new BigDecimal("100"),
                    new BigDecimal("-1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void allWeightsZeroProducesEmpty() {
    var accounts =
        List.of(new SubAccount("A", 0.0), new SubAccount("B", 0.0), new SubAccount("C", 0.0));
    var results =
        calculator.allocate(accounts, AllocationMethod.PRO_RATA, new BigDecimal("100"), AVG_PRICE);
    assertThat(results).isEmpty();
  }

  private static void assertSumEquals(List<AllocationResult> results, String expected) {
    var sum =
        results.stream()
            .map(AllocationResult::allocatedQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).as("Σ allocations must equal parent quantity").isEqualByComparingTo(expected);
  }
}
