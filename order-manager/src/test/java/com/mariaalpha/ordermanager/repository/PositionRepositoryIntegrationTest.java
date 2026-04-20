package com.mariaalpha.ordermanager.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mariaalpha.ordermanager.entity.PositionEntity;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PositionRepositoryIntegrationTest {

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

  @BeforeEach
  void reset() {
    positionRepository.deleteAll();
  }

  @Test
  void savesAndRetrievesBySymbol() {
    var p = new PositionEntity("AAPL");
    p.setNetQuantity(BigDecimal.valueOf(100));
    p.setAvgEntryPrice(BigDecimal.valueOf(150));
    positionRepository.save(p);

    assertThat(positionRepository.findById("AAPL")).isPresent();
  }

  @Test
  void findForUpdateReturnsPosition() {
    var p = new PositionEntity("MSFT");
    p.setNetQuantity(BigDecimal.valueOf(50));
    p.setAvgEntryPrice(BigDecimal.valueOf(300));
    positionRepository.save(p);

    assertThat(positionRepository.findForUpdate("MSFT")).isPresent();
    assertThat(positionRepository.findForUpdate("UNKNOWN")).isEmpty();
  }

  @Test
  void findAllOpenExcludesFlatPositions() {
    var open = new PositionEntity("AAPL");
    open.setNetQuantity(BigDecimal.valueOf(100));
    open.setAvgEntryPrice(BigDecimal.valueOf(150));
    var flat = new PositionEntity("MSFT");
    flat.setNetQuantity(BigDecimal.ZERO);
    flat.setAvgEntryPrice(BigDecimal.ZERO);
    positionRepository.save(open);
    positionRepository.save(flat);

    List<PositionEntity> results = positionRepository.findAllOpen();
    assertThat(results).hasSize(1).allMatch(p -> p.getSymbol().equals("AAPL"));
  }

  @Test
  void findAllOpenIncludesShorts() {
    var shortP = new PositionEntity("TSLA");
    shortP.setNetQuantity(BigDecimal.valueOf(-50));
    shortP.setAvgEntryPrice(BigDecimal.valueOf(200));
    positionRepository.save(shortP);

    List<PositionEntity> results = positionRepository.findAllOpen();
    assertThat(results).hasSize(1).allMatch(p -> p.getNetQuantity().signum() < 0);
  }
}
