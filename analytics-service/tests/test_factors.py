"""Factor decomposition tests — exposures, Euler contributions, PCA, saturation warning."""

from __future__ import annotations

import numpy as np
import pytest

from analytics.portfolio.covariance import to_correlation
from analytics.portfolio.factors import (
    MARKET,
    SECTOR_PREFIX,
    SIZE,
    VOLATILITY,
    _zscore,
    build_exposures,
    decompose,
    estimate_factor_model,
    pca,
)

from .conftest import BETAS, UNIVERSE


@pytest.fixture
def cov(reference) -> np.ndarray:
    return reference.prior_covariance(UNIVERSE)


class TestZscore:
    def test_standardises_to_zero_mean_unit_variance(self) -> None:
        z = _zscore(np.array([1.0, 2.0, 3.0, 4.0]))
        assert z.mean() == pytest.approx(0.0, abs=1e-15)
        assert z.std() == pytest.approx(1.0, abs=1e-12)

    def test_degenerate_cross_section_scores_flat_rather_than_exploding(self) -> None:
        assert np.allclose(_zscore(np.full(5, 7.0)), 0.0)


class TestBuildExposures:
    def test_market_column_is_beta(self, reference, cov) -> None:
        volatilities = np.sqrt(np.diag(cov))
        exposures, names = build_exposures(UNIVERSE, reference, volatilities)
        assert names[0] == MARKET
        assert np.allclose(exposures[:, 0], BETAS, atol=1e-12)

    def test_size_and_volatility_are_cross_sectional_zscores(self, reference, cov) -> None:
        volatilities = np.sqrt(np.diag(cov))
        exposures, names = build_exposures(UNIVERSE, reference, volatilities)
        for factor in (SIZE, VOLATILITY):
            column = exposures[:, names.index(factor)]
            assert column.mean() == pytest.approx(0.0, abs=1e-12)

    def test_sector_dummies_sum_to_one_per_asset(self, reference, cov) -> None:
        volatilities = np.sqrt(np.diag(cov))
        exposures, names = build_exposures(UNIVERSE, reference, volatilities)
        sector_cols = [i for i, n in enumerate(names) if n.startswith(SECTOR_PREFIX)]
        assert np.allclose(exposures[:, sector_cols].sum(axis=1), 1.0, atol=1e-15)

    def test_momentum_is_added_only_when_supplied(self, reference, cov) -> None:
        volatilities = np.sqrt(np.diag(cov))
        _, without = build_exposures(UNIVERSE, reference, volatilities)
        _, with_momentum = build_exposures(
            UNIVERSE, reference, volatilities, momentum=np.arange(len(UNIVERSE), dtype=float)
        )
        assert "MOMENTUM" not in without
        assert "MOMENTUM" in with_momentum


class TestEstimateFactorModel:
    def test_projection_fallback_reproduces_the_covariance_diagonal(self, reference, cov) -> None:
        model = estimate_factor_model(UNIVERSE, reference, cov)
        fitted = model.exposures @ model.factor_covariance @ model.exposures.T
        assert np.allclose(np.diag(fitted) + model.specific_variance, np.diag(cov), rtol=1e-8)

    def test_specific_variance_is_non_negative(self, reference, cov, factor_returns) -> None:
        model = estimate_factor_model(UNIVERSE, reference, cov, factor_returns)
        assert (model.specific_variance >= 0).all()

    def test_factor_covariance_is_symmetric(self, reference, cov, factor_returns) -> None:
        model = estimate_factor_model(UNIVERSE, reference, cov, factor_returns)
        assert np.allclose(model.factor_covariance, model.factor_covariance.T, atol=1e-15)


class TestPca:
    def test_variance_explained_sums_to_one(self, cov) -> None:
        _, correlation = to_correlation(cov)
        result = pca(np.full(6, 1 / 6), correlation, np.sqrt(np.diag(cov)), UNIVERSE)
        assert result.variance_explained.sum() == pytest.approx(1.0, abs=1e-12)

    def test_components_are_ordered_by_decreasing_variance(self, cov) -> None:
        _, correlation = to_correlation(cov)
        result = pca(np.full(6, 1 / 6), correlation, np.sqrt(np.diag(cov)), UNIVERSE)
        assert np.all(np.diff(result.variance_explained) <= 1e-12)

    def test_cumulative_reaches_one(self, cov) -> None:
        _, correlation = to_correlation(cov)
        result = pca(np.full(6, 1 / 6), correlation, np.sqrt(np.diag(cov)), UNIVERSE)
        assert result.cumulative_variance_explained[-1] == pytest.approx(1.0, abs=1e-12)

    def test_single_factor_data_is_almost_entirely_explained_by_pc1(self) -> None:
        rng = np.random.default_rng(2)
        factor = rng.normal(0, 1, size=(500, 1))
        returns = factor @ np.ones((1, 6)) * 0.01 + rng.normal(0, 0.0002, size=(500, 6))
        correlation = np.corrcoef(returns, rowvar=False)
        result = pca(np.full(6, 1 / 6), correlation, np.full(6, 0.2), UNIVERSE)
        assert result.variance_explained[0] > 0.95

    def test_identity_correlation_spreads_variance_evenly(self) -> None:
        result = pca(np.full(4, 0.25), np.eye(4), np.full(4, 0.2), ("A", "B", "C", "D"))
        assert np.allclose(result.variance_explained, 0.25, atol=1e-12)

    def test_top_component_symbols_are_ranked_by_absolute_loading(self, cov) -> None:
        _, correlation = to_correlation(cov)
        result = pca(np.full(6, 1 / 6), correlation, np.sqrt(np.diag(cov)), UNIVERSE)
        loadings = [abs(v) for _, v in result.top_component_symbols]
        assert loadings == sorted(loadings, reverse=True)

    def test_payload_is_json_shaped(self, cov) -> None:
        _, correlation = to_correlation(cov)
        payload = pca(np.full(6, 1 / 6), correlation, np.sqrt(np.diag(cov)), UNIVERSE).to_payload()
        assert set(payload) == {
            "varianceExplained",
            "cumulativeVarianceExplained",
            "portfolioLoadings",
            "topComponentSymbols",
        }


class TestDecompose:
    def test_market_exposure_is_the_weighted_average_beta(self, reference, cov) -> None:
        _, correlation = to_correlation(cov)
        weights = np.full(6, 1 / 6)
        result = decompose(UNIVERSE, weights, cov, correlation, reference)
        assert result.exposures[MARKET] == pytest.approx(float(weights @ BETAS), rel=1e-9)

    def test_variance_contributions_sum_to_the_systematic_variance(self, reference, cov) -> None:
        _, correlation = to_correlation(cov)
        weights = np.array([0.4, 0.2, 0.1, 0.1, 0.1, 0.1])
        result = decompose(UNIVERSE, weights, cov, correlation, reference)
        assert sum(result.variance_contributions.values()) == pytest.approx(
            result.systematic_variance, rel=1e-9
        )

    def test_model_variance_is_the_sum_of_its_parts(self, reference, cov) -> None:
        _, correlation = to_correlation(cov)
        result = decompose(UNIVERSE, np.full(6, 1 / 6), cov, correlation, reference)
        assert result.model_variance == pytest.approx(
            result.systematic_variance + result.idiosyncratic_variance, abs=1e-18
        )

    def test_systematic_and_idiosyncratic_percentages_sum_to_one(self, reference, cov) -> None:
        _, correlation = to_correlation(cov)
        result = decompose(UNIVERSE, np.full(6, 1 / 6), cov, correlation, reference)
        payload = result.to_payload()
        assert payload["systematicVariancePct"] + payload["idiosyncraticVariancePct"] == (
            pytest.approx(1.0, abs=1e-6)
        )

    def test_saturated_model_is_flagged_rather_than_reported_as_a_finding(
        self, reference, cov
    ) -> None:
        """Six factors over six assets span the whole return space by construction.

        Everything then classifies as systematic, which is an artefact of the setup rather than a
        property of the book. The decomposition must say so.
        """
        _, correlation = to_correlation(cov)
        result = decompose(UNIVERSE, np.full(6, 1 / 6), cov, correlation, reference)
        assert any("saturated" in note for note in result.notes)
        assert result.model_fit == pytest.approx(1.0, rel=1e-6)

    def test_notes_flag_the_missing_return_history(self, reference, cov) -> None:
        _, correlation = to_correlation(cov)
        result = decompose(UNIVERSE, np.full(6, 1 / 6), cov, correlation, reference)
        assert any("no live return history" in note for note in result.notes)

    def test_live_returns_change_the_estimate_and_drop_the_history_note(
        self, reference, cov, factor_returns
    ) -> None:
        _, correlation = to_correlation(cov)
        result = decompose(
            UNIVERSE, np.full(6, 1 / 6), cov, correlation, reference, returns=factor_returns
        )
        assert not any("no live return history" in note for note in result.notes)
        assert "MOMENTUM" in result.factors

    def test_payload_is_json_shaped(self, reference, cov) -> None:
        _, correlation = to_correlation(cov)
        payload = decompose(UNIVERSE, np.full(6, 1 / 6), cov, correlation, reference).to_payload()
        for key in (
            "exposures",
            "varianceContributions",
            "systematicVariance",
            "modelVariance",
            "covarianceVariance",
            "modelFit",
            "pca",
            "notes",
        ):
            assert key in payload
