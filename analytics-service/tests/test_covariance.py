"""Covariance estimator tests — closed forms, shrinkage bounds, PSD repair, annualisation."""

from __future__ import annotations

import numpy as np
import pytest

from analytics.portfolio.covariance import (
    CovarianceEstimate,
    EstimatorSettings,
    annualize,
    constant_correlation_target,
    estimate,
    ewma_covariance,
    ewma_weights,
    from_supplied,
    ledoit_wolf_intensity,
    repair_psd,
    sample_covariance,
    to_correlation,
)

from .conftest import ANNUAL_VOLS, BAR_SECONDS, SECONDS_PER_YEAR, UNIVERSE


class TestSampleCovariance:
    def test_matches_hand_computed_two_by_two(self) -> None:
        returns = np.array([[1.0, 2.0], [3.0, 5.0], [5.0, 4.0], [7.0, 9.0]])
        result = sample_covariance(returns)
        expected = np.cov(returns, rowvar=False, ddof=1)
        assert np.allclose(result, expected, atol=1e-12)

    def test_is_symmetric_and_has_non_negative_diagonal(self, factor_returns: np.ndarray) -> None:
        result = sample_covariance(factor_returns)
        assert np.allclose(result, result.T, atol=1e-15)
        assert (np.diag(result) >= 0).all()

    def test_rejects_too_few_rows(self) -> None:
        with pytest.raises(ValueError, match="more rows than ddof"):
            sample_covariance(np.array([[1.0, 2.0]]))

    def test_rejects_one_dimensional_input(self) -> None:
        with pytest.raises(ValueError, match="2-D return matrix"):
            sample_covariance(np.array([1.0, 2.0, 3.0]))


class TestEwma:
    def test_weights_sum_to_one(self) -> None:
        assert ewma_weights(50, 0.94).sum() == pytest.approx(1.0, abs=1e-15)

    def test_weights_are_increasing_so_recent_observations_dominate(self) -> None:
        weights = ewma_weights(20, 0.90)
        assert np.all(np.diff(weights) > 0)

    def test_lambda_one_degenerates_to_equal_weights(self) -> None:
        weights = ewma_weights(10, 1.0)
        assert np.allclose(weights, 0.1, atol=1e-15)

    def test_lambda_near_one_approaches_the_sample_covariance(
        self, factor_returns: np.ndarray
    ) -> None:
        ewma = ewma_covariance(factor_returns, lam=0.999999)
        sample = sample_covariance(factor_returns, ddof=0)
        assert np.allclose(ewma, sample, rtol=1e-3)

    @pytest.mark.parametrize("lam", [0.0, -0.5, 1.5])
    def test_rejects_lambda_outside_zero_to_one(self, lam: float) -> None:
        with pytest.raises(ValueError, match="lambda must be"):
            ewma_weights(10, lam)

    def test_rejects_single_row(self) -> None:
        with pytest.raises(ValueError, match="at least two rows"):
            ewma_covariance(np.array([[1.0, 2.0]]))


class TestConstantCorrelationTarget:
    def test_preserves_the_sample_variances(self, factor_returns: np.ndarray) -> None:
        sample = sample_covariance(factor_returns)
        target = constant_correlation_target(sample)
        assert np.allclose(np.diag(target), np.diag(sample), atol=1e-15)

    def test_off_diagonal_correlation_is_the_sample_mean(self) -> None:
        sample = np.array([[4.0, 1.0, 0.0], [1.0, 9.0, 3.0], [0.0, 3.0, 16.0]])
        std = np.sqrt(np.diag(sample))
        corr = sample / np.outer(std, std)
        expected_mean = corr[~np.eye(3, dtype=bool)].mean()

        target = constant_correlation_target(sample)
        target_corr = target / np.outer(std, std)
        off = target_corr[~np.eye(3, dtype=bool)]
        assert np.allclose(off, expected_mean, atol=1e-12)


class TestLedoitWolfIntensity:
    def test_is_within_zero_and_one(self, factor_returns: np.ndarray) -> None:
        sample = sample_covariance(factor_returns)
        target = constant_correlation_target(sample)
        intensity = ledoit_wolf_intensity(factor_returns, sample, target)
        assert 0.0 <= intensity <= 1.0

    def test_shrinks_harder_when_there_is_less_data(self, factor_returns: np.ndarray) -> None:
        def intensity_for(rows: int) -> float:
            window = factor_returns[:rows]
            sample = sample_covariance(window)
            return ledoit_wolf_intensity(window, sample, constant_correlation_target(sample))

        assert intensity_for(20) > intensity_for(400)

    def test_returns_one_when_the_target_already_equals_the_sample(self) -> None:
        returns = np.random.default_rng(1).normal(size=(50, 3))
        sample = sample_covariance(returns)
        assert ledoit_wolf_intensity(returns, sample, sample) == 1.0

    def test_degenerate_shapes_shrink_completely(self) -> None:
        returns = np.random.default_rng(2).normal(size=(1, 3))
        assert ledoit_wolf_intensity(returns, np.eye(3), np.eye(3)) == 1.0


class TestRepairPsd:
    def test_leaves_a_psd_matrix_untouched(self) -> None:
        matrix = np.array([[4.0, 1.0], [1.0, 9.0]])
        repaired, was_repaired = repair_psd(matrix)
        assert not was_repaired
        assert np.allclose(repaired, matrix, atol=1e-15)

    def test_repairs_an_indefinite_correlation_matrix(self) -> None:
        # rho_12 = rho_13 = 0.9 but rho_23 = -0.9 is jointly impossible.
        indefinite = np.array([[1.0, 0.9, 0.9], [0.9, 1.0, -0.9], [0.9, -0.9, 1.0]])
        assert np.linalg.eigvalsh(indefinite).min() < 0

        repaired, was_repaired = repair_psd(indefinite)
        assert was_repaired
        assert np.linalg.eigvalsh(repaired).min() >= -1e-12

    def test_preserves_the_diagonal_so_marginal_volatilities_survive(self) -> None:
        indefinite = np.array([[4.0, 3.5, 3.5], [3.5, 9.0, -8.0], [3.5, -8.0, 16.0]])
        repaired, was_repaired = repair_psd(indefinite)
        assert was_repaired
        assert np.allclose(np.diag(repaired), np.diag(indefinite), rtol=1e-9)

    def test_result_is_symmetric(self) -> None:
        indefinite = np.array([[1.0, 0.95, 0.95], [0.95, 1.0, -0.95], [0.95, -0.95, 1.0]])
        repaired, _ = repair_psd(indefinite)
        assert np.allclose(repaired, repaired.T, atol=1e-14)

    def test_empty_matrix_is_a_no_op(self) -> None:
        repaired, was_repaired = repair_psd(np.zeros((0, 0)))
        assert repaired.size == 0
        assert not was_repaired


class TestAnnualize:
    def test_scales_per_bar_variance_to_annual(self) -> None:
        target_annual_vol = 0.28
        bars_per_year = SECONDS_PER_YEAR / BAR_SECONDS
        per_bar_var = (target_annual_vol**2) / bars_per_year
        annual = annualize(np.array([[per_bar_var]]), BAR_SECONDS, SECONDS_PER_YEAR)
        assert np.sqrt(annual[0, 0]) == pytest.approx(target_annual_vol, rel=1e-12)

    def test_rejects_non_positive_bar_seconds(self) -> None:
        with pytest.raises(ValueError, match="bar_seconds must be positive"):
            annualize(np.eye(2), 0, SECONDS_PER_YEAR)


class TestToCorrelation:
    def test_splits_a_covariance_into_vols_and_unit_diagonal_correlation(self) -> None:
        cov = np.array([[0.04, 0.012], [0.012, 0.09]])
        vols, corr = to_correlation(cov)
        assert np.allclose(vols, [0.2, 0.3], atol=1e-15)
        assert np.allclose(np.diag(corr), 1.0, atol=1e-15)
        assert corr[0, 1] == pytest.approx(0.012 / (0.2 * 0.3), rel=1e-12)

    def test_zero_variance_asset_does_not_divide_by_zero(self) -> None:
        vols, corr = to_correlation(np.array([[0.0, 0.0], [0.0, 0.09]]))
        assert vols[0] == 0.0
        assert np.isfinite(corr).all()


class TestEstimate:
    def test_falls_back_to_the_prior_without_enough_observations(
        self, reference, estimator_settings
    ) -> None:
        result = estimate(UNIVERSE, reference, estimator_settings)
        assert result.source == "prior"
        assert result.shrinkage_intensity == 1.0
        assert result.symbols_from_prior == UNIVERSE
        assert np.allclose(result.volatilities, ANNUAL_VOLS, rtol=1e-9)

    def test_prior_only_estimate_is_positive_definite(self, reference, estimator_settings) -> None:
        result = estimate(UNIVERSE, reference, estimator_settings)
        assert np.linalg.eigvalsh(result.covariance).min() > 0

    def test_recovers_planted_volatilities_from_live_returns(
        self, reference, estimator_settings, factor_returns
    ) -> None:
        result = estimate(
            UNIVERSE, reference, estimator_settings, factor_returns, return_symbols=UNIVERSE
        )
        assert result.source == "sample+prior"
        assert result.observations == factor_returns.shape[0]
        # Shrinkage toward the prior biases the estimate, so this is a loose but meaningful band.
        assert np.allclose(result.volatilities, ANNUAL_VOLS, rtol=0.25)

    def test_respects_the_shrinkage_floor(self, reference, factor_returns) -> None:
        settings = EstimatorSettings(shrinkage_floor=0.75, min_observations=10)
        result = estimate(UNIVERSE, reference, settings, factor_returns, return_symbols=UNIVERSE)
        assert result.shrinkage_intensity >= 0.75

    def test_symbols_without_live_data_keep_their_prior_row(
        self, reference, estimator_settings, factor_returns
    ) -> None:
        live = UNIVERSE[:4]
        result = estimate(
            UNIVERSE,
            reference,
            estimator_settings,
            factor_returns[:, :4],
            return_symbols=live,
        )
        assert set(result.symbols_from_prior) == {"TSLA", "NVDA"}
        assert result.volatilities[4] == pytest.approx(0.55, rel=1e-9)

    def test_result_is_always_positive_semi_definite(
        self, reference, estimator_settings, factor_returns
    ) -> None:
        result = estimate(
            UNIVERSE, reference, estimator_settings, factor_returns, return_symbols=UNIVERSE
        )
        assert np.linalg.eigvalsh(result.covariance).min() >= -1e-12

    def test_rejects_an_empty_universe(self, reference, estimator_settings) -> None:
        with pytest.raises(ValueError, match="at least one symbol"):
            estimate((), reference, estimator_settings)

    def test_sample_estimator_is_also_supported(self, reference, factor_returns) -> None:
        settings = EstimatorSettings(estimator="sample", min_observations=10)
        result = estimate(UNIVERSE, reference, settings, factor_returns, return_symbols=UNIVERSE)
        assert result.source == "sample+prior"


class TestCovarianceEstimateHelpers:
    def _estimate(self, reference, settings) -> CovarianceEstimate:
        return estimate(UNIVERSE, reference, settings)

    def test_daily_scales_by_trading_days(self, reference, estimator_settings) -> None:
        result = self._estimate(reference, estimator_settings)
        assert np.allclose(result.daily(), result.covariance / 252.0, atol=1e-18)
        assert np.allclose(
            result.daily_volatilities(), result.volatilities / np.sqrt(252.0), atol=1e-15
        )

    def test_index_of(self, reference, estimator_settings) -> None:
        result = self._estimate(reference, estimator_settings)
        assert result.index_of("AAPL") == 0
        assert result.index_of("ZZZZ") is None

    def test_subset_selects_the_requested_block(self, reference, estimator_settings) -> None:
        result = self._estimate(reference, estimator_settings)
        subset = result.subset(["NVDA", "AAPL"])
        assert subset.symbols == ("NVDA", "AAPL")
        assert subset.covariance[0, 0] == pytest.approx(result.covariance[5, 5], rel=1e-12)
        assert subset.covariance[0, 1] == pytest.approx(result.covariance[5, 0], rel=1e-12)

    def test_subset_rejects_unknown_symbols(self, reference, estimator_settings) -> None:
        result = self._estimate(reference, estimator_settings)
        with pytest.raises(ValueError, match="not in the covariance estimate"):
            result.subset(["ZZZZ"])

    def test_payload_is_camel_case_and_json_shaped(self, reference, estimator_settings) -> None:
        payload = self._estimate(reference, estimator_settings).to_payload()
        assert set(payload) == {"symbols", "annualizedVolatility", "correlation", "diagnostics"}
        assert len(payload["correlation"]) == len(UNIVERSE)
        assert payload["diagnostics"]["source"] == "prior"


class TestFromSupplied:
    def test_wraps_a_caller_supplied_matrix(self) -> None:
        cov = np.array([[0.04, 0.012], [0.012, 0.09]])
        result = from_supplied(("A", "B"), cov)
        assert result.source == "supplied"
        assert np.allclose(result.volatilities, [0.2, 0.3], atol=1e-12)

    def test_rejects_the_wrong_shape(self) -> None:
        with pytest.raises(ValueError, match="covariance must be 2x2"):
            from_supplied(("A", "B"), np.eye(3))

    def test_rejects_non_finite_entries(self) -> None:
        with pytest.raises(ValueError, match="non-finite"):
            from_supplied(("A", "B"), np.array([[1.0, np.nan], [np.nan, 1.0]]))

    def test_repairs_an_indefinite_supplied_matrix_and_says_so(self) -> None:
        indefinite = np.array([[1.0, 0.9, 0.9], [0.9, 1.0, -0.9], [0.9, -0.9, 1.0]])
        result = from_supplied(("A", "B", "C"), indefinite)
        assert result.psd_repaired
        assert np.linalg.eigvalsh(result.covariance).min() >= -1e-12
