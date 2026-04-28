package com.mariaalpha.posttrade.tca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mariaalpha.posttrade.model.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TcaCalculatorTest {

  private static final BigDecimal EPS = new BigDecimal("0.01");

  private static TcaInputs inputs(
      Side side,
      double qty,
      double arrivalMid,
      double bid,
      double ask,
      double avgFill,
      double commission,
      double vwap) {
    return new TcaInputs(
        UUID.randomUUID(),
        "AAPL",
        "VWAP",
        side,
        BigDecimal.valueOf(qty),
        BigDecimal.valueOf(arrivalMid),
        BigDecimal.valueOf(bid),
        BigDecimal.valueOf(ask),
        BigDecimal.valueOf(avgFill),
        BigDecimal.valueOf(commission),
        BigDecimal.valueOf(vwap),
        Instant.parse("2026-04-20T09:30:00Z"),
        Instant.parse("2026-04-20T09:40:00Z"));
  }

  @Nested
  @DisplayName("slippage (bps)")
  class Slippage {

    @Test
    @DisplayName("BUY paying above arrival yields positive slippage")
    void buyAboveArrival() {
      // arrival=100, avgFill=100.10 → (100.10 - 100)/100 * 10000 = 10 bps
      TcaInputs in = inputs(Side.BUY, 1000, 100.00, 99.99, 100.01, 100.10, 0.0, 100.05);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.slippageBps()).isCloseTo(new BigDecimal("10.0000"), within(EPS));
    }

    @Test
    @DisplayName("BUY paying below arrival yields negative slippage (price improvement)")
    void buyBelowArrival() {
      // arrival=100, avgFill=99.95 → -5 bps
      TcaInputs in = inputs(Side.BUY, 1000, 100.00, 99.99, 100.01, 99.95, 0.0, 99.97);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.slippageBps()).isCloseTo(new BigDecimal("-5.0000"), within(EPS));
    }

    @Test
    @DisplayName("SELL receiving below arrival yields positive slippage")
    void sellBelowArrival() {
      // arrival=100, avgFill=99.90 → (100 - 99.90)/100 * 10000 = 10 bps
      TcaInputs in = inputs(Side.SELL, 1000, 100.00, 99.99, 100.01, 99.90, 0.0, 99.95);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.slippageBps()).isCloseTo(new BigDecimal("10.0000"), within(EPS));
    }

    @Test
    @DisplayName("SELL receiving above arrival yields negative slippage")
    void sellAboveArrival() {
      TcaInputs in = inputs(Side.SELL, 1000, 100.00, 99.99, 100.01, 100.05, 0.0, 100.02);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.slippageBps()).isCloseTo(new BigDecimal("-5.0000"), within(EPS));
    }

    @Test
    @DisplayName("zero slippage when avgFill equals arrival")
    void zeroSlippage() {
      TcaInputs in = inputs(Side.BUY, 1000, 100.00, 99.99, 100.01, 100.00, 0.0, 100.00);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.slippageBps()).isCloseTo(BigDecimal.ZERO, within(EPS));
    }

    @Test
    @DisplayName("null arrival returns null slippage")
    void nullArrival() {
      TcaInputs in =
          new TcaInputs(
              UUID.randomUUID(),
              "AAPL",
              "VWAP",
              Side.BUY,
              BigDecimal.TEN,
              null,
              null,
              null,
              new BigDecimal("100.10"),
              BigDecimal.ZERO,
              new BigDecimal("100.05"),
              Instant.now(),
              Instant.now());
      assertThat(TcaCalculator.compute(in).slippageBps()).isNull();
    }
  }

  @Nested
  @DisplayName("implementation shortfall (bps)")
  class ImplementationShortfall {

    @Test
    @DisplayName("BUY: IS = price slippage + commission, normalized")
    void buyIsWithCommission() {
      // arrival=100, avgFill=100.10, qty=1000, commission=$20
      // price component = (100.10-100)*1000 = 100
      // total $ cost = 100 + 20 = 120
      // notional = 100 * 1000 = 100000
      // IS_bps = 120/100000 * 10000 = 12 bps
      TcaInputs in = inputs(Side.BUY, 1000, 100.00, 99.99, 100.01, 100.10, 20.0, 100.05);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.implShortfallBps()).isCloseTo(new BigDecimal("12.0000"), within(EPS));
    }

    @Test
    @DisplayName("SELL: price improvement almost offsets commission")
    void sellIsWithCommission() {
      // signedCost(SELL, 50.02, 50.00) = (50.00 - 50.02) = -0.02
      // priceComponent = -0.02 * 1000 = -20
      // totalCost = -20 + 15 = -5
      // notional = 50 * 1000 = 50000
      // IS_bps = -5/50000 * 10000 = -1 bps
      TcaInputs in = inputs(Side.SELL, 1000, 50.00, 49.99, 50.01, 50.02, 15.0, 50.01);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.implShortfallBps()).isCloseTo(new BigDecimal("-1.0000"), within(EPS));
    }

    @Test
    @DisplayName("IS equals commission-bps when avgFill equals arrival")
    void isEqualsCommissionOnly() {
      // IS_$ = 0 + 10 = 10; notional = 100000; IS_bps = 1 bps
      TcaInputs in = inputs(Side.BUY, 1000, 100.00, 99.99, 100.01, 100.00, 10.0, 100.00);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.implShortfallBps()).isCloseTo(new BigDecimal("1.0000"), within(EPS));
    }

    @Test
    @DisplayName("zero commission: IS_bps == slippage_bps")
    void zeroCommissionMatchesSlippage() {
      TcaInputs in = inputs(Side.BUY, 500, 200.00, 199.99, 200.01, 200.40, 0.0, 200.20);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.implShortfallBps()).isCloseTo(r.slippageBps(), within(EPS));
    }
  }

  @Nested
  @DisplayName("VWAP benchmark (bps)")
  class VwapBenchmark {

    @Test
    @DisplayName("BUY above market VWAP is a cost")
    void buyAboveVwap() {
      // vwap=100, avgFill=100.05 → 5 bps
      TcaInputs in = inputs(Side.BUY, 1000, 100.00, 99.99, 100.01, 100.05, 0.0, 100.00);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.vwapBenchmarkBps()).isCloseTo(new BigDecimal("5.0000"), within(EPS));
    }

    @Test
    @DisplayName("SELL below market VWAP is a cost")
    void sellBelowVwap() {
      TcaInputs in = inputs(Side.SELL, 1000, 100.00, 99.99, 100.01, 99.90, 0.0, 100.00);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.vwapBenchmarkBps()).isCloseTo(new BigDecimal("10.0000"), within(EPS));
    }

    @Test
    @DisplayName("null VWAP price returns null benchmark")
    void nullVwap() {
      TcaInputs in =
          new TcaInputs(
              UUID.randomUUID(),
              "AAPL",
              "VWAP",
              Side.BUY,
              BigDecimal.TEN,
              new BigDecimal("100"),
              new BigDecimal("99.99"),
              new BigDecimal("100.01"),
              new BigDecimal("100.05"),
              BigDecimal.ZERO,
              null,
              Instant.now(),
              Instant.now());
      assertThat(TcaCalculator.compute(in).vwapBenchmarkBps()).isNull();
    }
  }

  @Nested
  @DisplayName("spread cost (bps)")
  class SpreadCost {

    @Test
    @DisplayName("half of a 2c spread on a $100 mid is 1 bps")
    void twoCentSpreadOnHundredDollarMid() {
      // (0.02 / 100) / 2 * 10000 = 1 bps
      TcaInputs in = inputs(Side.BUY, 1000, 100.00, 99.99, 100.01, 100.00, 0.0, 100.00);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.spreadCostBps()).isCloseTo(new BigDecimal("1.0000"), within(EPS));
    }

    @Test
    @DisplayName("crossed book yields zero spread cost")
    void zeroSpread() {
      TcaInputs in = inputs(Side.BUY, 1000, 100.00, 100.01, 100.00, 100.00, 0.0, 100.00);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.spreadCostBps()).isCloseTo(BigDecimal.ZERO, within(EPS));
    }

    @Test
    @DisplayName("wide spread on a penny stock yields many bps")
    void wideSpreadPennyStock() {
      // mid=$1.00, spread=$0.02 → (0.02/1.00)/2 * 10000 = 100 bps
      TcaInputs in = inputs(Side.BUY, 1000, 1.00, 0.99, 1.01, 1.00, 0.0, 1.00);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.spreadCostBps()).isCloseTo(new BigDecimal("100.0000"), within(EPS));
    }

    @Test
    @DisplayName("null bid/ask returns null spread cost")
    void nullBidAsk() {
      TcaInputs in =
          new TcaInputs(
              UUID.randomUUID(),
              "AAPL",
              "VWAP",
              Side.BUY,
              BigDecimal.TEN,
              new BigDecimal("100"),
              null,
              null,
              new BigDecimal("100"),
              BigDecimal.ZERO,
              new BigDecimal("100"),
              Instant.now(),
              Instant.now());
      assertThat(TcaCalculator.compute(in).spreadCostBps()).isNull();
    }
  }

  @Nested
  @DisplayName("full example — textbook buy order")
  class FullExample {

    @Test
    @DisplayName("consolidated textbook example: BUY 1000 AAPL with all four metrics")
    void buyTextbook() {
      // arrival_mid=$180.00, bid=$179.98, ask=$180.02, avgFill=$180.05
      // qty=1000, commission=$5, interval VWAP=$180.03
      //
      // slippage = (180.05 - 180.00)/180.00 * 10000 = 2.7778 bps
      // IS = ((180.05-180)*1000 + 5)/(180*1000) * 10000 = 55/180000 * 10000 = 3.0556 bps
      // vwap_bench = (180.05 - 180.03)/180.03 * 10000 = 1.1109 bps
      // spread_cost = (0.04 / 180.00) / 2 * 10000 = 1.1111 bps
      TcaInputs in = inputs(Side.BUY, 1000, 180.00, 179.98, 180.02, 180.05, 5.0, 180.03);
      TcaComputation r = TcaCalculator.compute(in);
      assertThat(r.slippageBps()).isCloseTo(new BigDecimal("2.7778"), within(EPS));
      assertThat(r.implShortfallBps()).isCloseTo(new BigDecimal("3.0556"), within(EPS));
      assertThat(r.vwapBenchmarkBps()).isCloseTo(new BigDecimal("1.1109"), within(EPS));
      assertThat(r.spreadCostBps()).isCloseTo(new BigDecimal("1.1111"), within(EPS));
    }
  }
}
