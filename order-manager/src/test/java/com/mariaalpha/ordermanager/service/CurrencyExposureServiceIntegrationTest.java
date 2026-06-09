package com.mariaalpha.ordermanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.ordermanager.config.CurrencyConfig;
import com.mariaalpha.ordermanager.entity.PositionEntity;
import com.mariaalpha.ordermanager.repository.PositionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Drives {@link CurrencyExposureService} against a real Postgres so the aggregation by currency
 * survives a real JPA round-trip (transactional view of the position table). Mirrors the pattern
 * the repository integration tests use.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Tag("integration")
class CurrencyExposureServiceIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("mariaalpha")
          .withUsername("mariaalpha")
          .withPassword("mariaalpha");

  @DynamicPropertySource
  static void dsProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
  }

  @Autowired private PositionRepository positionRepository;

  private CurrencyExposureService service;

  @BeforeEach
  void setUp() {
    positionRepository.deleteAll();
    var config =
        new CurrencyConfig(
            "USD", Map.of("7203", "JPY", "SAP", "EUR"), List.of("USD", "EUR", "JPY"));
    service = new CurrencyExposureService(positionRepository, config);
  }

  @Test
  void aggregatesPositionsByCurrencyAcrossPostgresRoundTrip() {
    positionRepository.save(position("AAPL", "100", "150", null));
    positionRepository.save(position("SAP", "50", "120", null));
    positionRepository.save(position("7203", "200", "2500", null));

    var resp = service.exposureByCurrency();

    assertThat(resp.rows()).extracting(r -> r.currency()).containsExactly("EUR", "JPY", "USD");
    assertThat(resp.rows().get(0).grossExposure()).isEqualByComparingTo("6000"); // EUR
    assertThat(resp.rows().get(1).grossExposure()).isEqualByComparingTo("500000"); // JPY
    assertThat(resp.rows().get(2).grossExposure()).isEqualByComparingTo("15000"); // USD
    assertThat(resp.openPositions()).isEqualTo(3);
  }

  @Test
  void unknownSymbolFallsBackToDefaultCurrency() {
    positionRepository.save(position("XYZ", "10", "100", null));

    var resp = service.exposureByCurrency();
    assertThat(resp.rows()).hasSize(1);
    assertThat(resp.rows().get(0).currency()).isEqualTo("USD");
    assertThat(resp.rows().get(0).grossExposure()).isEqualByComparingTo("1000");
  }

  @Test
  void closedPositionsContributePnlButNotExposure() {
    var realised = position("AAPL", "0", "150", null);
    realised.setRealizedPnl(new BigDecimal("750"));
    positionRepository.save(realised);

    var resp = service.exposureByCurrency();
    var usd = resp.rows().get(0);
    assertThat(usd.positionCount()).isZero();
    assertThat(usd.grossExposure()).isEqualByComparingTo("0");
    assertThat(usd.totalPnl()).isEqualByComparingTo("750");
    assertThat(resp.openPositions()).isZero();
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
