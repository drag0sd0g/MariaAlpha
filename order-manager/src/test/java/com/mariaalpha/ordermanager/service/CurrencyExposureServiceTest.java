package com.mariaalpha.ordermanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mariaalpha.ordermanager.config.CurrencyConfig;
import com.mariaalpha.ordermanager.entity.PositionEntity;
import com.mariaalpha.ordermanager.repository.PositionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CurrencyExposureServiceTest {

  @Mock private PositionRepository positionRepository;

  private CurrencyExposureService service;

  @BeforeEach
  void setUp() {
    var config =
        new CurrencyConfig(
            "USD", Map.of("7203", "JPY", "SAP", "EUR"), List.of("USD", "EUR", "JPY"));
    service = new CurrencyExposureService(positionRepository, config);
  }

  @Test
  void exposureByCurrencyReturnsEmptyRowsForEmptyPortfolio() {
    when(positionRepository.findAll()).thenReturn(List.of());
    var resp = service.exposureByCurrency();
    assertThat(resp.rows()).isEmpty();
    assertThat(resp.openPositions()).isZero();
  }

  @Test
  void singleUsdPositionLandsInUsdRow() {
    var aapl = position("AAPL", "100", "150", null);
    when(positionRepository.findAll()).thenReturn(List.of(aapl));

    var resp = service.exposureByCurrency();
    assertThat(resp.rows()).hasSize(1);
    var usd = resp.rows().get(0);
    assertThat(usd.currency()).isEqualTo("USD");
    assertThat(usd.positionCount()).isEqualTo(1);
    assertThat(usd.grossExposure()).isEqualByComparingTo("15000");
    assertThat(usd.netExposure()).isEqualByComparingTo("15000");
    assertThat(resp.openPositions()).isEqualTo(1);
  }

  @Test
  void overrideRoutesPositionToTargetCurrency() {
    var toyota = position("7203", "1000", "2500", null);
    when(positionRepository.findAll()).thenReturn(List.of(toyota));

    var resp = service.exposureByCurrency();
    assertThat(resp.rows()).hasSize(1);
    assertThat(resp.rows().get(0).currency()).isEqualTo("JPY");
    assertThat(resp.rows().get(0).grossExposure()).isEqualByComparingTo("2500000");
  }

  @Test
  void mixedCurrenciesAreAggregatedSeparately() {
    var aapl = position("AAPL", "100", "150", null);
    var sap = position("SAP", "50", "120", null);
    var toyota = position("7203", "200", "2500", null);

    when(positionRepository.findAll()).thenReturn(List.of(aapl, sap, toyota));

    var resp = service.exposureByCurrency();
    // Rows are sorted alphabetically (EUR, JPY, USD) for stable UI ordering.
    assertThat(resp.rows()).extracting(r -> r.currency()).containsExactly("EUR", "JPY", "USD");
    assertThat(resp.rows().get(0).grossExposure()).isEqualByComparingTo("6000"); // EUR
    assertThat(resp.rows().get(1).grossExposure()).isEqualByComparingTo("500000"); // JPY
    assertThat(resp.rows().get(2).grossExposure()).isEqualByComparingTo("15000"); // USD
    assertThat(resp.openPositions()).isEqualTo(3);
  }

  @Test
  void shortPositionContributesNegativeNetButPositiveGross() {
    var shortAapl = position("AAPL", "-100", "150", null);
    when(positionRepository.findAll()).thenReturn(List.of(shortAapl));

    var resp = service.exposureByCurrency();
    var usd = resp.rows().get(0);
    assertThat(usd.netExposure()).isEqualByComparingTo("-15000");
    assertThat(usd.grossExposure()).isEqualByComparingTo("15000");
  }

  @Test
  void usesLastMarkPriceWhenPresent() {
    var aapl = position("AAPL", "100", "150", "160");
    when(positionRepository.findAll()).thenReturn(List.of(aapl));

    var resp = service.exposureByCurrency();
    assertThat(resp.rows().get(0).grossExposure()).isEqualByComparingTo("16000");
  }

  @Test
  void flatPositionsContributePnlButNotExposureOrCount() {
    var flat = position("AAPL", "0", "150", null);
    flat.setRealizedPnl(new BigDecimal("250"));
    when(positionRepository.findAll()).thenReturn(List.of(flat));

    var resp = service.exposureByCurrency();
    assertThat(resp.rows()).hasSize(1);
    var usd = resp.rows().get(0);
    assertThat(usd.positionCount()).isZero();
    assertThat(usd.grossExposure()).isEqualByComparingTo("0");
    assertThat(usd.realizedPnl()).isEqualByComparingTo("250");
    assertThat(usd.totalPnl()).isEqualByComparingTo("250");
    assertThat(resp.openPositions()).isZero();
  }

  @Test
  void totalPnlIsSumOfRealizedAndUnrealized() {
    var aapl = position("AAPL", "100", "150", "155");
    aapl.setRealizedPnl(new BigDecimal("100"));
    aapl.setUnrealizedPnl(new BigDecimal("500"));
    when(positionRepository.findAll()).thenReturn(List.of(aapl));

    var resp = service.exposureByCurrency();
    assertThat(resp.rows().get(0).totalPnl()).isEqualByComparingTo("600");
  }

  private static PositionEntity position(String symbol, String netQty, String avg, String mark) {
    var p = new PositionEntity(symbol);
    p.setNetQuantity(new BigDecimal(netQty));
    p.setAvgEntryPrice(new BigDecimal(avg));
    if (mark != null) {
      p.setLastMarkPrice(new BigDecimal(mark));
    }
    return p;
  }
}
