package com.mariaalpha.posttrade.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mariaalpha.posttrade.allocation.SubAccountConfig.SubAccount;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubAccountRegistryTest {

  @Test
  void emptyConfigIsValidAndReportsNotConfigured() {
    var registry = new SubAccountRegistry(new SubAccountConfig(null, null));
    registry.validate();
    assertThat(registry.isConfigured()).isFalse();
    assertThat(registry.accounts()).isEmpty();
    assertThat(registry.defaultMethod()).isEqualTo(AllocationMethod.PRO_RATA);
  }

  @Test
  void validConfigPreservesDeclarationOrder() {
    var config =
        new SubAccountConfig(
            AllocationMethod.FIFO,
            List.of(
                new SubAccount("HOUSE", 50.0),
                new SubAccount("HF_A", 30.0),
                new SubAccount("HF_B", 20.0)));
    var registry = new SubAccountRegistry(config);
    registry.validate();
    assertThat(registry.accounts())
        .extracting(SubAccount::name)
        .containsExactly("HOUSE", "HF_A", "HF_B");
    assertThat(registry.defaultMethod()).isEqualTo(AllocationMethod.FIFO);
    assertThat(registry.isConfigured()).isTrue();
  }

  @Test
  void rejectsDuplicateName() {
    var config =
        new SubAccountConfig(
            AllocationMethod.PRO_RATA,
            List.of(new SubAccount("DUP", 1.0), new SubAccount("DUP", 2.0)));
    var registry = new SubAccountRegistry(config);
    assertThatThrownBy(registry::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate");
  }

  @Test
  void rejectsNonPositiveWeight() {
    var config =
        new SubAccountConfig(AllocationMethod.PRO_RATA, List.of(new SubAccount("BAD", -1.0)));
    var registry = new SubAccountRegistry(config);
    assertThatThrownBy(registry::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("weight");
  }

  @Test
  void rejectsBlankName() {
    var config = new SubAccountConfig(AllocationMethod.PRO_RATA, List.of(new SubAccount("", 1.0)));
    var registry = new SubAccountRegistry(config);
    assertThatThrownBy(registry::validate).isInstanceOf(IllegalStateException.class);
  }
}
