package com.mariaalpha.strategyengine.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mariaalpha.proto.signal.MarketRegime;
import com.mariaalpha.strategyengine.config.RegimeAutoSelectConfig;
import com.mariaalpha.strategyengine.ml.MlRegimeResult;
import com.mariaalpha.strategyengine.ml.MlSignalClient;
import com.mariaalpha.strategyengine.registry.StrategyRegistry;
import com.mariaalpha.strategyengine.strategy.TradingStrategy;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class RegimeBasedStrategySelectorTest {

  @Mock private MlSignalClient mlClient;
  @Mock private StrategyRegistry registry;
  @Mock private TradingStrategy momentum;
  @Mock private TradingStrategy vwap;

  @Test
  void returnsEmptyWhenAutoSelectDisabled() {
    var selector =
        new RegimeBasedStrategySelector(
            new RegimeAutoSelectConfig(false, 0.6, Map.of()), mlClient, registry);

    assertThat(selector.selectFor("AAPL")).isEmpty();
    verify(mlClient, never()).getRegime("AAPL");
  }

  @Test
  void selectsMomentumOnTrendingUpAboveThreshold() {
    when(mlClient.getRegime("AAPL"))
        .thenReturn(Optional.of(new MlRegimeResult(MarketRegime.TRENDING_UP, 0.82)));
    when(registry.get("MOMENTUM")).thenReturn(Optional.of(momentum));
    var selector =
        new RegimeBasedStrategySelector(
            new RegimeAutoSelectConfig(true, 0.6, Map.of()), mlClient, registry);

    assertThat(selector.selectFor("AAPL")).contains(momentum);
  }

  @Test
  void selectsVwapOnMeanRevertingAboveThreshold() {
    when(mlClient.getRegime("MSFT"))
        .thenReturn(Optional.of(new MlRegimeResult(MarketRegime.MEAN_REVERTING, 0.75)));
    when(registry.get("VWAP")).thenReturn(Optional.of(vwap));
    var selector =
        new RegimeBasedStrategySelector(
            new RegimeAutoSelectConfig(true, 0.6, Map.of()), mlClient, registry);

    assertThat(selector.selectFor("MSFT")).contains(vwap);
  }

  @Test
  void returnsEmptyWhenConfidenceBelowThreshold() {
    when(mlClient.getRegime("AAPL"))
        .thenReturn(Optional.of(new MlRegimeResult(MarketRegime.TRENDING_UP, 0.4)));
    var selector =
        new RegimeBasedStrategySelector(
            new RegimeAutoSelectConfig(true, 0.6, Map.of()), mlClient, registry);

    assertThat(selector.selectFor("AAPL")).isEmpty();
  }

  @Test
  void returnsEmptyOnUnmappedRegimeSoCallerFallsBackToManualBinding() {
    when(mlClient.getRegime("TSLA"))
        .thenReturn(Optional.of(new MlRegimeResult(MarketRegime.HIGH_VOLATILITY, 0.95)));
    var selector =
        new RegimeBasedStrategySelector(
            new RegimeAutoSelectConfig(true, 0.6, Map.of()), mlClient, registry);

    assertThat(selector.selectFor("TSLA")).isEmpty();
  }

  @Test
  void returnsEmptyWhenMlReturnsNoRegime() {
    when(mlClient.getRegime("AAPL")).thenReturn(Optional.empty());
    var selector =
        new RegimeBasedStrategySelector(
            new RegimeAutoSelectConfig(true, 0.6, Map.of()), mlClient, registry);

    assertThat(selector.selectFor("AAPL")).isEmpty();
  }

  @Test
  void honorsConfigOverridesOverDefaults() {
    var twap = vwap;
    when(mlClient.getRegime("GOOGL"))
        .thenReturn(Optional.of(new MlRegimeResult(MarketRegime.MEAN_REVERTING, 0.8)));
    when(registry.get("TWAP")).thenReturn(Optional.of(twap));
    var selector =
        new RegimeBasedStrategySelector(
            new RegimeAutoSelectConfig(true, 0.6, Map.of("MEAN_REVERTING", "TWAP")),
            mlClient,
            registry);

    assertThat(selector.selectFor("GOOGL")).contains(twap);
  }

  @Test
  void returnsEmptyWhenMappedStrategyIsNotRegistered() {
    when(mlClient.getRegime("AAPL"))
        .thenReturn(Optional.of(new MlRegimeResult(MarketRegime.TRENDING_UP, 0.9)));
    when(registry.get("MOMENTUM")).thenReturn(Optional.empty());
    var selector =
        new RegimeBasedStrategySelector(
            new RegimeAutoSelectConfig(true, 0.6, Map.of()), mlClient, registry);

    assertThat(selector.selectFor("AAPL")).isEmpty();
  }
}
