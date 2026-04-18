package com.mariaalpha.executionengine.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.executionengine.model.Order;
import com.mariaalpha.executionengine.model.OrderSignal;
import com.mariaalpha.executionengine.model.OrderType;
import com.mariaalpha.executionengine.model.RiskCheckResult;
import com.mariaalpha.executionengine.model.Side;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskCheckChainTest {

  @Test
  void allPassReturnsPass() {
    var check1 = mockCheck("Check1", true);
    var check2 = mockCheck("Check2", true);
    var check3 = mockCheck("Check3", true);
    var chain = new RiskCheckChain(List.of(check1, check2, check3));

    var result = chain.evaluate(createOrder());
    assertThat(result.passed()).isTrue();
    assertThat(result.checkName()).isEqualTo("ALL");
  }

  @Test
  void shortCircuitsOnFirstFailure() {
    var check1 = mockCheck("Check1", true);
    var check2 = mockCheck("Check2", false);
    var check3 = mockCheck("Check3", true);
    var chain = new RiskCheckChain(List.of(check1, check2, check3));

    chain.evaluate(createOrder());
    verify(check3, never()).check(any());
  }

  @Test
  void returnsFailingCheckNameAndReason() {
    var check1 = mockCheck("Check1", true);
    var failingCheck = mock(RiskCheck.class);
    when(failingCheck.name()).thenReturn("MaxNotional");
    when(failingCheck.check(any()))
        .thenReturn(RiskCheckResult.fail("MaxNotional", "Exceeds $100K"));
    var chain = new RiskCheckChain(List.of(check1, failingCheck));

    var result = chain.evaluate(createOrder());
    assertThat(result.passed()).isFalse();
    assertThat(result.checkName()).isEqualTo("MaxNotional");
    assertThat(result.reason()).isEqualTo("Exceeds $100K");
  }

  @Test
  void emptyChainPasses() {
    var chain = new RiskCheckChain(List.of());
    var result = chain.evaluate(createOrder());
    assertThat(result.passed()).isTrue();
  }

  private RiskCheck mockCheck(String name, boolean passes) {
    var check = mock(RiskCheck.class);
    when(check.name()).thenReturn(name);
    when(check.check(any()))
        .thenReturn(passes ? RiskCheckResult.pass(name) : RiskCheckResult.fail(name, "failed"));
    return check;
  }

  private Order createOrder() {
    return new Order(
        new OrderSignal(
            "AAPL", Side.BUY, 100, OrderType.MARKET, null, null, "VWAP", Instant.now()));
  }
}
